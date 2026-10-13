package androidx.core.view;

import android.view.View;

/** 仅隔离非受测的输入法/insets查询；真实 measure/layout/requestLayout 不替换。 */
public final class ViewCompat {
  public static WindowInsetsCompat getRootWindowInsets(View view) {
    return null;
  }
}
