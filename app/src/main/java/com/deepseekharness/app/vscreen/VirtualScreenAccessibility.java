package com.deepseekharness.app.vscreen;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.deepseekharness.app.util.AccessibilityDumpBounds;
import org.json.*;
import java.util.*;

/** 所有查询和动作均限定在虚拟 displayId，不使用主屏的 activeWindow。 */
public final class VirtualScreenAccessibility {
  private final Map<String, AccessibilityNodeInfo> nodes = new LinkedHashMap<>();
  private int treeDisplay = -1;
  private long treeAt;

  VirtualScreenAccessibility() {}

  public synchronized JSONObject run(
      AccessibilityService service, int displayId, String operation, JSONObject args) {
    AccessibilityNodeInfo root = null, target = null;
    boolean[] submitted = {false};
    try {
      if (service == null) return fail("ACCESSIBILITY_UNAVAILABLE");
      if (android.os.Build.VERSION.SDK_INT < 30 || displayId <= 0) return fail("INVALID_DISPLAY");
      root = com.deepseekharness.app.DshaAccessibilityService.virtualRoot(displayId);
      if (root == null) return fail("VIRTUAL_WINDOW_UNAVAILABLE");
      com.deepseekharness.app.DshaAccessibilityService.validateActionRoot(displayId, root);
      if (operation.equals("tree")) {
        clear();
        treeDisplay = displayId;
        treeAt = android.os.SystemClock.elapsedRealtime();
        JSONArray tree = new JSONArray();
        walk(root, tree, 0);
        JSONObject result = ok().put("nodes", tree);
        if (tree.length() == 0) result.put("treeUnavailable", "EMPTY_ACCESSIBILITY_TREE");
        return result;
      }
      if (operation.equals("node")) {
        if (displayId != treeDisplay || android.os.SystemClock.elapsedRealtime() - treeAt > 30000)
          return fail("STALE_TREE");
        target = nodes.remove(args.optString("nodeId"));
        if (target == null || !target.refresh() || target.getWindowId() != root.getWindowId())
          return fail("STALE_NODE");
        String action = args.optString("action");
        boolean result;
        switch (action) {
          case "click":
            result = perform(target, submitted, AccessibilityNodeInfo.ACTION_CLICK);
            break;
          case "long_click":
            result = perform(target, submitted, AccessibilityNodeInfo.ACTION_LONG_CLICK);
            break;
          case "scroll_forward":
            result = perform(target, submitted, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            break;
          case "scroll_backward":
            result = perform(target, submitted, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            break;
          case "focus":
            result = perform(target, submitted, AccessibilityNodeInfo.ACTION_FOCUS);
            break;
          case "set_text":
            Bundle b = new Bundle();
            String value = args.optString("text");
            if (value.length() > 16000) return fail("TEXT_LIMIT");
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            result = perform(target, submitted, AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            break;
          default:
            return fail("INVALID_NODE_ACTION");
        }
        return result ? ok() : fail("NODE_ACTION_REJECTED");
      }
      target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
      if (target == null || !target.isEditable()) return fail("VIRTUAL_INPUT_NOT_FOCUSED");
      if (target.isPassword()) return fail("PASSWORD_INPUT_UNAVAILABLE");
      String id = target.getWindowId() + ":" + target.hashCode();
      if (operation.equals("editor"))
        return ok().put("editorId", id)
            .put("text", target.getText() == null ? "" : target.getText().toString())
            .put("start", target.getTextSelectionStart())
            .put("end", target.getTextSelectionEnd())
            .put("inputType", target.getInputType())
            .put("multiline", target.isMultiLine());
      if (!id.equals(args.optString("editorId"))) return fail("EDITOR_CHANGED");
      if (operation.equals("submit"))
        return perform(
                target,
                submitted,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())
            ? ok()
            : fail("IME_ACTION_UNAVAILABLE");
      if (!operation.equals("edit")) return fail("INVALID_EDITOR_OPERATION");
      String text = args.optString("text");
      int start = args.optInt("start", text.length()), end = args.optInt("end", start);
      if (text.length() > 16000
          || start < 0
          || end < 0
          || start > text.length()
          || end > text.length()) return fail("INVALID_EDITOR_RANGE");
      Bundle b = new Bundle();
      b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
      if (!perform(target, submitted, AccessibilityNodeInfo.ACTION_SET_TEXT, b))
        return fail("EDITOR_WRITE_REJECTED");
      b = new Bundle();
      b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start);
      b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end);
      boolean selected = perform(target, submitted, AccessibilityNodeInfo.ACTION_SET_SELECTION, b);
      return ok().put("selectionApplied", selected);
    } catch (com.deepseekharness.app.DshaAccessibilityService.TargetChangedException rejected) {
      // 动作之前目标/运行状态就变了：动作没有落地，给调用方一个「未执行」的稳定码（#140）。
      return fail("SCREEN_TARGET_CHANGED");
    } catch (Throwable error) {
      JSONObject value = fail("ACCESSIBILITY_OPERATION_UNKNOWN");
      if (submitted[0])
        try {
          value.put("executionUnknown", true);
        } catch (Exception ignored) {
        }
      return value;
    } finally {
      if (target != null) target.recycle();
      if (root != null) root.recycle();
    }
  }

  private static boolean perform(AccessibilityNodeInfo target, boolean[] submitted, int action) {
    return perform(target, submitted, action, null);
  }

  private static boolean perform(
      AccessibilityNodeInfo target, boolean[] submitted, int action, Bundle args) {
    submitted[0] = true;
    return target.performAction(action, args);
  }

  public synchronized void clear() {
    for (var node : nodes.values()) node.recycle();
    nodes.clear();
    treeDisplay = -1;
  }

  private void walk(AccessibilityNodeInfo node, JSONArray tree, int depth) throws JSONException {
    if (depth > 24 || tree.length() >= 200) return;
    String text =
        node.isPassword() ? "" : String.valueOf(node.getText() == null ? "" : node.getText());
    String description =
        node.isPassword()
            ? ""
            : String.valueOf(
                node.getContentDescription() == null ? "" : node.getContentDescription());
    Rect rect = new Rect();
    node.getBoundsInScreen(rect);
    // 与主屏 dump 同一条不变量：反序矩形不是「空节点」，它的四条边都不能用来定位，
    // 报出去只会让调用方按一个错坐标点屏幕（issue #143）。
    if (AccessibilityDumpBounds.reportable(
        !text.isEmpty() || !description.isEmpty(),
        node.isClickable() || node.isEditable() || node.isScrollable(),
        rect.left,
        rect.top,
        rect.right,
        rect.bottom)) {
      String id = UUID.randomUUID().toString();
      nodes.put(id, AccessibilityNodeInfo.obtain(node));
      tree.put(
          new JSONObject()
              .put("nodeId", id)
              .put("text", text.substring(0, Math.min(text.length(), 1000)))
              .put(
                  "description",
                  node.isPassword()
                      ? ""
                      : String.valueOf(
                          node.getContentDescription() == null ? "" : node.getContentDescription()))
              .put("clickable", node.isClickable())
              .put("editable", node.isEditable())
              .put("scrollable", node.isScrollable())
              .put(
                  "bounds",
                  new JSONArray(new int[] {rect.left, rect.top, rect.right, rect.bottom})));
    }
    for (int i = 0; i < node.getChildCount() && tree.length() < 200; i++) {
      AccessibilityNodeInfo child = node.getChild(i);
      if (child != null)
        try {
          walk(child, tree, depth + 1);
        } finally {
          child.recycle();
        }
    }
  }

  private static JSONObject ok() throws JSONException {
    return new JSONObject().put("ok", true);
  }

  private static JSONObject fail(String error) {
    try {
      return new JSONObject().put("ok", false).put("error", error);
    } catch (JSONException e) {
      return new JSONObject();
    }
  }
}
