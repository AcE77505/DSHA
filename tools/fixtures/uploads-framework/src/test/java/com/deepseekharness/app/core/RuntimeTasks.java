package com.deepseekharness.app.core;

/** 测试不启动 DSH；仅隔离运行任务门禁，与生产文件/布局实现无关。 */
public final class RuntimeTasks implements AutoCloseable {
  public static RuntimeTasks begin() {
    return new RuntimeTasks();
  }

  @Override
  public void close() {}
}
