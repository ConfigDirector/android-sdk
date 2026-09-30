package com.configdirector.internal.testing;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConfigDirectorContext;
import com.configdirector.EvaluationReason;
import com.configdirector.LogLevel;
import com.configdirector.RecordingLogger;
import java.util.Arrays;
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

/** The in-memory connection as a Java test would drive it: no coroutines, no Android environment. */
public class InMemoryConnectionJavaTest {

  private final RecordingLogger logger = new RecordingLogger(LogLevel.DEBUG);
  private InMemoryConnection connection;

  // The callbacks are handed back on the main thread, which a JVM test does not have.
  @Before
  public void setUpMainDispatcher() {
    TestDispatchers.setMain(Dispatchers.INSTANCE, Dispatchers.getDefault());
  }

  @After
  public void tearDown() {
    if (connection != null) {
      connection.getClient().close();
    }
    TestDispatchers.resetMain(Dispatchers.INSTANCE);
  }

  private InMemoryConnection connection(Map<String, Object> values) {
    connection = new InMemoryConnection(values, 3_000L, logger);
    return connection;
  }

  private static void initialize(ConfigDirectorClient client, ConfigDirectorContext context)
      throws InterruptedException {
    CountDownLatch initialized = new CountDownLatch(1);
    client.initialize(context, initialized::countDown);
    assertThat(initialized.await(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  public void servesEveryKindOfValueGivenFromJava() throws InterruptedException {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("flag", true);
    values.put("count", 20);
    values.put("big", 3_000_000_000L);
    values.put("ratio", 2.5);
    values.put("name", "Ada");
    values.put("settings", Collections.singletonMap("theme", "dark"));
    values.put("tags", Arrays.asList("x", "y"));
    ConfigDirectorClient client = connection(values).getClient();

    initialize(client, null);

    assertThat(client.isReady()).isTrue();
    assertThat(client.getBoolean("flag", false)).isTrue();
    assertThat(client.getInt("count", 0)).isEqualTo(20);
    assertThat(client.getDouble("big", 0.0)).isEqualTo(3e9);
    assertThat(client.getDouble("ratio", 0.0)).isEqualTo(2.5);
    assertThat(client.getString("name", "")).isEqualTo("Ada");
    assertThat(client.getJsonObject("settings", Collections.emptyMap())).containsEntry("theme", "dark");
    assertThat(client.getJsonArray("tags", Collections.emptyList())).containsExactly("x", "y").inOrder();
  }

  @Test
  public void changesReachWatchesAndReads() throws InterruptedException {
    InMemoryConnection connection = connection(Collections.singletonMap("flag", true));
    ConfigDirectorClient client = connection.getClient();
    List<Boolean> seen = new CopyOnWriteArrayList<>();
    client.watchBoolean("flag", false, seen::add);
    initialize(client, null);
    waitFor("the seeded value", () -> seen.size() == 2);

    connection.setValue("flag", false);
    waitFor("the new value", () -> seen.size() == 3);

    assertThat(client.getBoolean("flag", true)).isFalse();
    assertThat(seen).containsExactly(false, true, false).inOrder();

    connection.removeValue("flag");

    assertThat(client.evaluateBoolean("flag", true).getReason())
        .isEqualTo(EvaluationReason.CONFIG_STATE_MISSING);
  }

  @Test
  public void holdsAndCompletesInitialization() throws InterruptedException {
    InMemoryConnection connection = connection(Collections.singletonMap("flag", true));
    ConfigDirectorClient client = connection.getClient();
    connection.holdInitialization();
    CountDownLatch initialized = new CountDownLatch(1);

    client.initialize(ConfigDirectorContext.builder().id("user-a").build(), initialized::countDown);
    assertThat(initialized.await(200, TimeUnit.MILLISECONDS)).isFalse();
    assertThat(client.isReady()).isFalse();

    connection.completeInitialization();

    assertThat(client.isReady()).isTrue();
    assertThat(initialized.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(connection.getContextUpdates())
        .containsExactly(ConfigDirectorContext.builder().id("user-a").build());
  }

  @Test
  public void failsInitializationLikeAnInvalidKey() throws InterruptedException {
    InMemoryConnection connection = connection(Collections.emptyMap());
    connection.failInitialization();

    initialize(connection.getClient(), null);

    assertThat(connection.getClient().isReady()).isFalse();
    assertThat(logger.messagesContaining("An error occurred during initialization")).hasSize(1);
  }

  @Test
  public void reportsTheSdkVersion() {
    assertThat(InMemoryConnection.getSdkVersion()).isNotEmpty();
  }

  @Test
  public void rejectsANullValue() {
    InMemoryConnection connection = connection(Collections.emptyMap());

    assertThrows(NullPointerException.class, () -> connection.setValue("flag", null));
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
