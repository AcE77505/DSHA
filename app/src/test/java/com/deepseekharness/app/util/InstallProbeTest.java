package com.deepseekharness.app.util;

import org.junit.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class InstallProbeTest {
  @Test
  public void eachSelectedStepOnlyProbesItsOwnCommands() {
    Set<Integer> seen = new HashSet<>();
    for (InstallProbe.Check check : InstallProbe.checks(0)) seen.add(check.step);
    assertEquals(Set.of(2, 3, 4, 5, 6), seen);
    for (int step = 2; step <= 6; step++)
      for (InstallProbe.Check check : InstallProbe.checks(step)) assertEquals(step, check.step);
    assertTrue(InstallProbe.checks(1).isEmpty());
  }

  @Test
  public void successfulLookingOutputIsNotSuccessfulExitCode() {
    InstallProbe.Results result = new InstallProbe.Results(InstallProbe.checks(3));
    assertFalse(result.accept("v24.0.0"));
    assertFalse(result.ok(3));
    result.accept("DSHA_CHECK_RESULT:node:127");
    assertFalse(result.ok(3));
    assertTrue(result.detail(3).contains("127"));
  }

  @Test
  public void missingDuplicateAndUnknownResultsFailClosed() {
    InstallProbe.Results result = new InstallProbe.Results(InstallProbe.checks(2));
    assertFalse(result.accept("DSHA_CHECK_RESULT:evil:0"));
    result.accept("DSHA_CHECK_RESULT:curl:0");
    result.accept("DSHA_CHECK_RESULT:git:0");
    assertFalse(result.ok(2));
    result.accept("DSHA_CHECK_RESULT:python:0");
    assertTrue(result.ok(2));
    result.accept("DSHA_CHECK_RESULT:python:0");
    assertFalse(result.ok(2));
  }

  @Test
  public void invalidExitStatusCannotBecomeSuccess() {
    for (String code : new String[] {"bogus", "-1", "256", "0:0"}) {
      InstallProbe.Results result = new InstallProbe.Results(InstallProbe.checks(3));
      result.accept("DSHA_CHECK_RESULT:node:" + code);
      assertFalse(result.ok(3));
    }
  }

  @Test
  public void readOnlyProbePreservesExitStatusWithoutHeadOrRepairCommands() {
    String script = InstallProbe.script(InstallProbe.checks(0));
    assertTrue(script.contains("code=$?"));
    assertTrue(script.contains("timeout --kill-after=5s 20s"));
    for (String bad :
        List.of(
            "apt-get", "ensure", "head -1", "rm -", "pip install", "npm install", "flatten-l2s"))
      assertFalse(bad, script.contains(bad));
    assertTrue(script.contains("PYTHONDONTWRITEBYTECODE=1"));
  }

  @Test
  public void sharedBudgetsCoverPnpmColdStartAndEverySelectedChildDeadline() {
    assertEquals(370_000, InstallProbe.plan(InstallProbe.checks(0)).outerTimeoutMs);
    assertEquals(125_000, InstallProbe.plan(InstallProbe.checks(4)).outerTimeoutMs);
    assertEquals(130_000, InstallProbe.plan(InstallProbe.checks(6)).outerTimeoutMs);
    String script = InstallProbe.plan(InstallProbe.checks(0)).script;
    assertTrue(script.contains("timeout --kill-after=5s 90s /bin/bash -c 'pnpm --version'"));
    assertTrue(script.contains("timeout --kill-after=5s 30s"));
    assertFalse(script.contains("else /bin/bash"));
  }

  @Test
  public void taskIdentityBeginOrderAndMonotonicDurationAreRequiredForProduction() {
    var plan = InstallProbe.plan(InstallProbe.checks(4));
    var clock = new java.util.concurrent.atomic.AtomicLong(1_000_000);
    var results = new InstallProbe.Results(plan, clock::get);
    assertFalse(results.accept("DSHA_CHECK_RESULT:pnpm:0:old-task"));
    assertNull(results.beginning("DSHA_CHECK_BEGIN:pnpm:old-task:90000"));
    assertNotNull(results.beginning("DSHA_CHECK_BEGIN:pnpm:" + plan.taskId + ":90000"));
    clock.addAndGet(25_000_000_000L); // longer than old budget, valid within new budget
    assertTrue(results.diagnostic().contains("current=pnpm"));
    assertTrue(results.diagnostic().contains("hostObservedMs=25000"));
    assertTrue(results.accept("DSHA_CHECK_RESULT:pnpm:0:" + plan.taskId));
    assertTrue(results.ok(4));
    assertNotNull(results.beginning("DSHA_CHECK_BEGIN:pnpm:" + plan.taskId + ":90000"));
    assertFalse(results.ok(4));
    var missingBegin = new InstallProbe.Results(plan);
    missingBegin.accept("DSHA_CHECK_RESULT:pnpm:0:" + plan.taskId);
    assertFalse(missingBegin.ok(4));
  }

  @Test
  public void newExclusivePublishAliasIsRecognizedAndNeverRewrittenAsRename() {
    InstallProbe.Check session =
        InstallProbe.checks(6).stream()
            .filter(c -> c.key.equals("session"))
            .findFirst()
            .orElseThrow();
    assertTrue(
        session.command.contains(
            "&& ! grep -Fq " + ShellQuote.arg(InstallProbe.SESSION_PUBLISH_IMPORT)));
    assertTrue(
        session.command.contains(
            "&& ! grep -Fq " + ShellQuote.arg(InstallProbe.SESSION_DIRECT_HINT_MARKER)));
  }
}
