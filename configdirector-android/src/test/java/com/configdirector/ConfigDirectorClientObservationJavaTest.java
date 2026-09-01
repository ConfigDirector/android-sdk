package com.configdirector;

import static com.google.common.truth.Truth.assertThat;

import java.util.Collections;
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
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ConfigDirectorClientObservationJavaTest {

  private final FakeSdkServer server = new FakeSdkServer();

  private final ConfigDirectorClient client =
      new ConfigDirectorClient(
          RuntimeEnvironment.getApplication(),
          "client-sdk-key",
          ClientOptions.builder()
              .logger(new RecordingLogger(LogLevel.OFF))
              .connection(ConnectionOptions.builder().baseUrl(server.getBaseUrl()).build())
              .build());

  private final ConfigDirectorContext proContext =
      ConfigDirectorContext.builder().name("Ada").trait("plan", "pro").build();

  @Before
  public void setUpMainDispatcher() {
    TestDispatchers.setMain(Dispatchers.INSTANCE, Dispatchers.getDefault());
  }

  @After
  public void tearDown() {
    client.close();
    server.close();
    TestDispatchers.resetMain(Dispatchers.INSTANCE);
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  public void handsAWatchTheCurrentValueAndEachChange() throws InterruptedException {
    List<Boolean> values = new CopyOnWriteArrayList<>();
    CountDownLatch delivered = new CountDownLatch(2);
    client.watchBoolean(
        "dark-mode",
        false,
        value -> {
          values.add(value);
          delivered.countDown();
        });

    client.initialize(proContext, () -> {});

    await(delivered);
    assertThat(values).containsExactly(false, true).inOrder();
  }

  @Test
  public void watchesAJsonConfig() throws InterruptedException {
    List<Map<String, Object>> documents = new CopyOnWriteArrayList<>();
    CountDownLatch delivered = new CountDownLatch(2);
    client.watchJsonObject(
        "theme",
        Collections.<String, Object>emptyMap(),
        document -> {
          documents.add(document);
          delivered.countDown();
        });

    client.initialize(proContext, () -> {});

    await(delivered);
    assertThat(documents.get(0)).isEmpty();
    assertThat(documents.get(1)).containsEntry("primary", "#101010");
  }

  @Test
  public void stopsWatchingOnceTheSubscriptionIsClosed() throws InterruptedException {
    List<Boolean> values = new CopyOnWriteArrayList<>();
    CountDownLatch firstValue = new CountDownLatch(1);
    Subscription subscription =
        client.watchBoolean(
            "dark-mode",
            false,
            value -> {
              values.add(value);
              firstValue.countDown();
            });
    await(firstValue);

    subscription.close();
    CountDownLatch initialized = new CountDownLatch(1);
    client.initialize(proContext, initialized::countDown);
    await(initialized);

    assertThat(values).containsExactly(false);
  }

  @Test
  public void closesAWatchWithTryWithResources() throws InterruptedException {
    List<String> values = new CopyOnWriteArrayList<>();
    CountDownLatch firstValue = new CountDownLatch(1);

    try (Subscription subscription =
        client.watchString(
            "welcome-message",
            "fallback",
            value -> {
              values.add(value);
              firstValue.countDown();
            })) {
      await(firstValue);
      assertThat(subscription).isNotNull();
    }

    assertThat(values).containsExactly("fallback");
  }

  @Test
  public void tellsAListenerWhatTheClientDid() throws InterruptedException {
    List<ClientEvent> events = new CopyOnWriteArrayList<>();
    // Ready, ConfigsUpdated and ContextUpdated, in that order: the context takes effect once the
    // connection carrying it has come back.
    CountDownLatch published = new CountDownLatch(3);
    client.addEventListener(
        event -> {
          events.add(event);
          published.countDown();
        });

    client.initialize(proContext, () -> {});

    await(published);
    ClientEvent.Ready readyEvent = null;
    ClientEvent.ConfigsUpdated configsUpdated = null;
    ClientEvent.ContextUpdated contextUpdated = null;
    for (ClientEvent event : events) {
      if (event instanceof ClientEvent.Ready) {
        readyEvent = (ClientEvent.Ready) event;
      } else if (event instanceof ClientEvent.ConfigsUpdated) {
        configsUpdated = (ClientEvent.ConfigsUpdated) event;
      } else if (event instanceof ClientEvent.ContextUpdated) {
        contextUpdated = (ClientEvent.ContextUpdated) event;
      }
    }

    assertThat(readyEvent).isNotNull();
    assertThat(readyEvent.getReason()).isEqualTo(ConnectReason.INITIALIZATION);
    assertThat(configsUpdated).isNotNull();
    assertThat(configsUpdated.getKeys()).contains("dark-mode");
    assertThat(contextUpdated).isNotNull();
    assertThat(contextUpdated.getContext()).isEqualTo(proContext);
  }

  @Test
  public void tellsAListenerAboutAConfigItServed() throws InterruptedException {
    CountDownLatch initialized = new CountDownLatch(1);
    client.initialize(proContext, initialized::countDown);
    await(initialized);

    List<ConfigEvaluation> evaluations = new CopyOnWriteArrayList<>();
    CountDownLatch evaluated = new CountDownLatch(1);
    client.addEvaluationListener(
        evaluation -> {
          evaluations.add(evaluation);
          evaluated.countDown();
        });

    assertThat(client.getBoolean("dark-mode", false)).isTrue();

    await(evaluated);
    ConfigEvaluation evaluation = evaluations.get(0);
    assertThat(evaluation.getKey()).isEqualTo("dark-mode");
    assertThat(evaluation.getValue()).isEqualTo(true);
    assertThat(evaluation.getValueId()).isEqualTo("dark-mode-pro");
    assertThat(evaluation.isDefaultValue()).isFalse();
    assertThat(evaluation.getReason()).isEqualTo(EvaluationReason.FOUND_MATCH);
    assertThat(evaluation.getReason().getWireName()).isEqualTo("found-match");
    assertThat(evaluation.getContext()).isEqualTo(proContext);
  }
}
