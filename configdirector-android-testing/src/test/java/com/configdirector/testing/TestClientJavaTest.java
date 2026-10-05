package com.configdirector.testing;

import static com.google.common.truth.Truth.assertThat;

import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConfigDirectorContext;
import com.configdirector.EvaluationReason;
import com.configdirector.LogLevel;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.test.TestDispatchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The test client as a Java test would drive it: no coroutines, no Android environment. */
public class TestClientJavaTest {

  private final RecordingLogger logger = new RecordingLogger(LogLevel.DEBUG);
  private TestClient testClient;

  // The callbacks are handed back on the main thread, which a JVM test does not have.
  @Before
  public void setUpMainDispatcher() {
    TestDispatchers.setMain(Dispatchers.INSTANCE, Dispatchers.getDefault().limitedParallelism(1, "main"));
  }

  @After
  public void tearDown() {
    if (testClient != null) {
      testClient.getClient().close();
    }
    TestDispatchers.resetMain(Dispatchers.INSTANCE);
  }

  private static void initialize(ConfigDirectorClient client, ConfigDirectorContext context)
      throws InterruptedException {
    CountDownLatch initialized = new CountDownLatch(1);
    client.initialize(context, initialized::countDown);
    assertThat(initialized.await(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  public void createsATestClientWithNoValues() throws InterruptedException {
    testClient = ConfigDirectorTesting.createTestClient();
    ConfigDirectorClient client = testClient.getClient();
    assertThat(client.isReady()).isFalse();

    initialize(client, null);

    assertThat(client.isReady()).isTrue();
    assertThat(client.evaluateBoolean("flag", false).getReason())
        .isEqualTo(EvaluationReason.CONFIG_STATE_MISSING);
  }

  @Test
  public void takesAMapOfAnyValueType() throws InterruptedException {
    Map<String, Boolean> flags = Collections.singletonMap("flag", true);
    testClient = ConfigDirectorTesting.createTestClient(flags);
    ConfigDirectorClient client = testClient.getClient();

    initialize(client, null);

    assertThat(client.getBoolean("flag", false)).isTrue();
  }

  @Test
  public void servesEveryKindOfValue() throws InterruptedException {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("flag", true);
    values.put("count", 20);
    values.put("ratio", 2.5);
    values.put("name", "Ada");
    values.put("settings", Collections.singletonMap("theme", "dark"));
    testClient = ConfigDirectorTesting.createTestClient(values);
    ConfigDirectorClient client = testClient.getClient();

    initialize(client, null);

    assertThat(client.getBoolean("flag", false)).isTrue();
    assertThat(client.getInt("count", 0)).isEqualTo(20);
    assertThat(client.getDouble("ratio", 0.0)).isEqualTo(2.5);
    assertThat(client.getString("name", "")).isEqualTo("Ada");
    assertThat(client.getJsonObject("settings", Collections.emptyMap())).containsEntry("theme", "dark");
  }

  @Test
  public void takesATimeoutAndALogger() throws InterruptedException {
    testClient = ConfigDirectorTesting.createTestClient(Collections.emptyMap(), 100L, logger);
    testClient.holdInitialization();

    initialize(testClient.getClient(), null);

    assertThat(testClient.getClient().isReady()).isFalse();
    assertThat(logger.messagesContaining("An error occurred during initialization")).hasSize(1);
  }

  @Test
  public void changesReachWatchesAndReads() throws InterruptedException {
    testClient = ConfigDirectorTesting.createTestClient(Collections.singletonMap("flag", true), 3_000L, logger);
    ConfigDirectorClient client = testClient.getClient();
    List<Boolean> seen = new CopyOnWriteArrayList<>();
    client.watchBoolean("flag", false, seen::add);
    initialize(client, null);
    waitFor("the seeded value", () -> seen.size() == 2);

    testClient.setValue("flag", false);
    waitFor("the new value", () -> seen.size() == 3);
    assertThat(seen).containsExactly(false, true, false).inOrder();

    testClient.removeValue("flag");
    assertThat(client.evaluateBoolean("flag", true).getReason())
        .isEqualTo(EvaluationReason.CONFIG_STATE_MISSING);

    testClient.replaceValues(Collections.singletonMap("count", 3));
    assertThat(client.getInt("count", 0)).isEqualTo(3);
  }

  @Test
  public void holdsCompletesAndFailsAttempts() throws InterruptedException {
    testClient = ConfigDirectorTesting.createTestClient(Collections.singletonMap("flag", true), 3_000L, logger);
    ConfigDirectorClient client = testClient.getClient();
    ConfigDirectorContext userA = ConfigDirectorContext.builder().id("user-a").build();
    ConfigDirectorContext userB = ConfigDirectorContext.builder().id("user-b").build();
    testClient.holdInitialization();
    CountDownLatch initialized = new CountDownLatch(1);

    client.initialize(userA, initialized::countDown);
    assertThat(initialized.await(200, TimeUnit.MILLISECONDS)).isFalse();
    testClient.completeInitialization();
    assertThat(client.isReady()).isTrue();
    assertThat(initialized.await(5, TimeUnit.SECONDS)).isTrue();

    testClient.holdContextUpdate();
    CountDownLatch updated = new CountDownLatch(1);
    client.updateContext(userB, updated::countDown);
    assertThat(updated.await(200, TimeUnit.MILLISECONDS)).isFalse();
    testClient.completeContextUpdate();
    assertThat(updated.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(client.getContext()).isEqualTo(userB);

    testClient.failContextUpdate();
    CountDownLatch failed = new CountDownLatch(1);
    client.updateContext(userA, failed::countDown);
    assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(client.isReady()).isFalse();
    assertThat(logger.messagesContaining("An error occurred during context update")).hasSize(1);

    testClient.failInitialization();
    initialize(client, userA);
    assertThat(client.isReady()).isFalse();
    assertThat(logger.messagesContaining("An error occurred during initialization")).hasSize(1);

    assertThat(testClient.getContextUpdates()).containsExactly(userA, userB, userA, userA).inOrder();
  }

  private static void waitFor(String description, java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(5);
    }
    throw new AssertionError("Timed out waiting for " + description);
  }
}
