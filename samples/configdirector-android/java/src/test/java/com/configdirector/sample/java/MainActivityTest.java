package com.configdirector.sample.java;

import static com.google.common.truth.Truth.assertThat;

import android.widget.TextView;
import com.configdirector.testing.TestClient;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * The screen over the test client {@link TestSampleApplication} hands it. The SDK calls the
 * watches, listeners and completion callbacks back on the main thread, which Robolectric only runs
 * when the test idles it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = TestSampleApplication.class)
public class MainActivityTest {

  private TestSampleApplication application;
  private TestClient testClient;
  private ActivityController<MainActivity> controller;
  private MainActivity activity;

  @Before
  public void startTheScreen() {
    application = (TestSampleApplication) RuntimeEnvironment.getApplication();
    testClient = application.testClient;
    waitForTheLog("initialize finished, ready=true");

    controller = Robolectric.buildActivity(MainActivity.class).setup();
    activity = controller.get();
    settle();
  }

  @After
  public void tearDown() {
    controller.destroy();
    testClient.getClient().close();
  }

  private static void settle() {
    ShadowLooper.shadowMainLooper().idle();
  }

  private static void waitFor(String description, BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      settle();
      try {
        Thread.sleep(10);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted waiting for " + description, interrupted);
      }
    }
    throw new AssertionError("Timed out waiting for " + description);
  }

  private void waitForTheLog(String line) {
    waitFor("the log line '" + line + "'", () -> application.log().text().contains(line));
  }

  private String textOf(int viewId) {
    return ((TextView) activity.findViewById(viewId)).getText().toString();
  }

  private void click(int viewId) {
    activity.findViewById(viewId).performClick();
    settle();
  }

  @Test
  public void showsEveryWatchedConfigOnceTheClientIsReady() {
    assertThat(textOf(R.id.status)).contains("ready=true");
    assertThat(textOf(R.id.log)).contains("initialize finished, ready=true");

    String watched = textOf(R.id.watched_configs);
    assertThat(watched).contains("temporary-feature-flag = false");
    assertThat(watched).contains("permanent-kill-switch = true");
    assertThat(watched).contains("day-of-the-week-config = Monday");
    assertThat(watched).contains("integer-config = 42");
    assertThat(watched).contains("integer-config as a double = 42.0");
    assertThat(watched).contains("json-value-config = {theme=dark}");
  }

  @Test
  public void reRendersAWatchedConfigWhenTheTestClientChangesItsValue() {
    testClient.setValue("day-of-the-week-config", "Tuesday");
    settle();

    assertThat(textOf(R.id.watched_configs)).contains("day-of-the-week-config = Tuesday");
    assertThat(textOf(R.id.watched_configs)).doesNotContain("Monday");
  }

  @Test
  public void handsTheDefaultToTheWatchesWhenTheTestClientRemovesAConfig() {
    testClient.removeValue("integer-config");
    settle();

    assertThat(textOf(R.id.watched_configs)).contains("integer-config = 10");
    assertThat(textOf(R.id.watched_configs)).contains("integer-config as a double = 0.0");
    assertThat(textOf(R.id.log)).contains("'integer-config' fell back to 10 (config-state-missing");
  }

  @Test
  public void switchesTheIdentityConfigsAreEvaluatedAgainstFromTheButtons() {
    click(R.id.user_beta_tester);
    waitForTheLog("updateContext finished, ready=true");
    settle();

    assertThat(testClient.getContextUpdates().get(1).getId()).isEqualTo("beta-tester");
    assertThat(textOf(R.id.status)).contains("context=Beta Tester {role=beta}");
    assertThat(textOf(R.id.log)).contains("updateContext finished, ready=true");
  }

  @Test
  public void readsEveryConfigOnRequest() {
    click(R.id.read_every_config);

    String log = textOf(R.id.log);
    assertThat(log).contains("temporary-feature-flag=false");
    assertThat(log).contains("day-of-the-week-config=Monday");
    assertThat(log).contains("json-value-config={\"theme\":\"dark\"}");
    assertThat(log).contains("json-value-config as a map={theme=dark}");
    assertThat(log).contains("no-such-config=fallback");
    assertThat(log).contains("integer-config as boolean=true");
    assertThat(log).contains("'no-such-config' fell back to fallback (config-state-missing");
    assertThat(log).contains("'integer-config' fell back to true (type-mismatch");
    assertThat(log).contains("'integer-config' served 42 (valueId");
  }

  @Test
  public void closesTheClientOnRequest() {
    click(R.id.close_client);

    assertThat(textOf(R.id.status)).contains("ready=false");
    assertThat(textOf(R.id.log)).contains("client closed, ready=false");

    testClient.setValue("day-of-the-week-config", "Tuesday");
    settle();
    assertThat(textOf(R.id.watched_configs)).contains("day-of-the-week-config = Monday");
  }
}
