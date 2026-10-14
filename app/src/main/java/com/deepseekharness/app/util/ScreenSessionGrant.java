package com.deepseekharness.app.util;

/** 授权绑定本次运行；撤销后，旧弹窗结果也不能重新授权。 */
public final class ScreenSessionGrant {
  private long generation = -1, revision;
  private boolean paused;

  public synchronized boolean paused() {
    return paused;
  }

  public synchronized void setPaused(boolean value) {
    paused = value;
    revoke();
  }

  public synchronized long revision() {
    return revision;
  }

  public synchronized boolean allowed(long current) {
    return !paused && current > 0 && generation == current;
  }

  public synchronized boolean accept(long current, long observedRevision) {
    if (paused || current <= 0 || observedRevision != revision) return false;
    generation = current;
    return true;
  }

  /** Both one-shot sensitive confirmation and remembered grants have the same stale-result gate. */
  public synchronized boolean completeConfirmation(
      boolean accepted,
      long observedGeneration,
      long currentGeneration,
      long observedRevision,
      boolean sessionActive,
      boolean remember) {
    if (paused
        || !accepted
        || !sessionActive
        || observedGeneration <= 0
        || observedGeneration != currentGeneration
        || observedRevision != revision) return false;
    if (remember) generation = currentGeneration;
    return true;
  }

  public synchronized void revoke() {
    generation = -1;
    revision++;
  }
}
