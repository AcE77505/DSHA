package com.deepseekharness.app.util;

/** Complete raw output lines only; a truncated or oversized line can never acquire a new prefix. */
public final class WebAuthLineBuffer {
  private static final int LIMIT = 16_384;
  private final StringBuilder pending = new StringBuilder();
  private boolean discarded;

  public String append(String chunk) {
    StringBuilder complete = new StringBuilder();
    for (int i = 0; i < chunk.length(); i++) {
      char value = chunk.charAt(i);
      if (value == '\n') {
        emit(complete);
      } else if (!discarded) {
        if (pending.length() >= LIMIT) {
          pending.setLength(0);
          discarded = true;
        } else pending.append(value);
      }
    }
    return complete.toString();
  }

  public String finish() {
    StringBuilder complete = new StringBuilder();
    emit(complete);
    return complete.toString();
  }

  private void emit(StringBuilder complete) {
    if (!discarded && pending.length() > 0) {
      int end = pending.length();
      if (pending.charAt(end - 1) == '\r') end--;
      complete.append(pending, 0, end).append('\n');
    }
    pending.setLength(0);
    discarded = false;
  }
}
