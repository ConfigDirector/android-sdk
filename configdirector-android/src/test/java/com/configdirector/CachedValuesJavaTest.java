package com.configdirector;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.test.TestDispatchers;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The values kept on the device as a Java app meets them: the event, the reason and the option. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CachedValuesJavaTest {

  private static final String CACHED_VALUES_KEY =
      "cached-values:24913e1336d021b669358fca9ac7212a3a52d53f77b98dca11ecb0e4441ab498";

  private final FakeSdkServer server = new FakeSdkServer();
  private final List<ConfigDirectorClient> clients = new CopyOnWriteArrayList<>();

  private final ConfigDirectorContext proContext =
      ConfigDirectorContext.builder().id("user-123").trait("plan", "pro").build();

  @Before
  public void setUpMainDispatcher() {
    TestDispatchers.setMain(Dispatchers.INSTANCE, Dispatchers.getDefault().limitedParallelism(1, "main"));
  }

  @After
  public void tearDown() {
    for (ConfigDirectorClient client : clients) {
      client.close();
    }
    server.close();
    TestDispatchers.resetMain(Dispatchers.INSTANCE);
  }

  private ConfigDirectorClient client(String baseUrl, CachingOptions caching) {
    ConfigDirectorClient client =
        new ConfigDirectorClient(
            RuntimeEnvironment.getApplication(),
            "client-sdk-key",
            ClientOptions.builder()
                .logger(new RecordingLogger(LogLevel.OFF))
                .connection(ConnectionOptions.builder().baseUrl(baseUrl).timeoutMillis(300L).build())
                .caching(caching)
                .build());
    clients.add(client);
    return client;
  }

  private static String unreachableBaseUrl() throws Exception {
    MockWebServer closed = new MockWebServer();
    closed.start();
    String baseUrl = closed.url("/").toString();
    closed.shutdown();
    return baseUrl;
  }

  private static String keptEntry() {
    return RuntimeEnvironment.getApplication()
        .getSharedPreferences("configdirector-sdk", Context.MODE_PRIVATE)
        .getString(CACHED_VALUES_KEY, null);
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
  }

  private void earlierLaunch(CachingOptions caching) throws InterruptedException {
    ConfigDirectorClient earlier = client(server.getBaseUrl(), caching);
    CountDownLatch initialized = new CountDownLatch(1);
    earlier.initialize(proContext, initialized::countDown);
    await(initialized);
    assertThat(earlier.isReady()).isTrue();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (keptEntry() == null && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    earlier.close();
  }

  @Test
  public void servesTheKeptValuesAndTellsAListenerTheyLoaded() throws Exception {
    earlierLaunch(CachingOptions.defaults());
    ConfigDirectorClient client = client(unreachableBaseUrl(), CachingOptions.defaults());
    List<ClientEvent> events = new CopyOnWriteArrayList<>();
    CountDownLatch loaded = new CountDownLatch(1);
    client.addEventListener(
        event -> {
          events.add(event);
          if (event instanceof ClientEvent.CachedValuesLoaded) {
            loaded.countDown();
          }
        });
    CountDownLatch initialized = new CountDownLatch(1);

    client.initialize(proContext, initialized::countDown);

    await(loaded);
    await(initialized);
    ConfigEvaluation evaluation = client.evaluateBoolean("dark-mode", false);
    assertThat(evaluation.getValue()).isEqualTo(true);
    assertThat(evaluation.getReason()).isEqualTo(EvaluationReason.CACHED);
    assertThat(evaluation.isDefaultValue()).isFalse();
    assertThat(client.isReady()).isFalse();
    assertThat(events.get(0)).isSameInstanceAs(ClientEvent.CachedValuesLoaded.INSTANCE);
    assertThat(events.get(1)).isInstanceOf(ClientEvent.ConfigsUpdated.class);
  }

  @Test
  public void keepsNothingWhenCachingIsTurnedOff() throws Exception {
    CachingOptions off = CachingOptions.builder().enabled(false).build();
    ConfigDirectorClient client = client(server.getBaseUrl(), off);
    CountDownLatch initialized = new CountDownLatch(1);

    client.initialize(proContext, initialized::countDown);

    await(initialized);
    Thread.sleep(200);
    assertThat(client.getBoolean("dark-mode", false)).isTrue();
    assertThat(keptEntry()).isNull();
  }
}
