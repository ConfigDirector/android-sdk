package com.configdirector;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
public class ConfigDirectorClientJavaTest {

  private final RecordingLogger logger = new RecordingLogger(LogLevel.DEBUG);
  private final FakeSdkServer server = new FakeSdkServer();

  private ConfigDirectorClient client() {
    return new ConfigDirectorClient(
        RuntimeEnvironment.getApplication(),
        "client-sdk-key",
        ClientOptions.builder()
            .logger(logger)
            .connection(ConnectionOptions.builder().baseUrl(server.getBaseUrl()).build())
            .build());
  }

  // The callbacks are handed back on the main thread, which a JVM test does not have.
  @Before
  public void setUpMainDispatcher() {
    TestDispatchers.setMain(Dispatchers.INSTANCE, Dispatchers.getDefault());
  }

  @After
  public void tearDown() {
    server.close();
    TestDispatchers.resetMain(Dispatchers.INSTANCE);
  }

  @Test
  public void servesTheConfigStateItReceivedOnceInitialized() throws InterruptedException {
    ConfigDirectorClient client = client();
    CountDownLatch initialized = new CountDownLatch(1);

    client.initialize(
        ConfigDirectorContext.builder().id("user-123").name("Ada").trait("plan", "pro").build(),
        initialized::countDown);

    assertThat(initialized.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(client.isReady()).isTrue();
    assertThat(client.getBoolean("dark-mode", false)).isTrue();
    assertThat(client.getString("welcome-message", "fallback")).isEqualTo("Hello, Ada");
    assertThat(client.getInt("max-items", 0)).isEqualTo(25);
    assertThat(client.getDouble("sample-rate", 0.0)).isEqualTo(0.25);
    client.close();
  }

  @Test
  public void readsAJsonConfigAsMapsAndLists() throws InterruptedException {
    ConfigDirectorClient client = client();
    CountDownLatch initialized = new CountDownLatch(1);
    client.initialize(null, initialized::countDown);
    assertThat(initialized.await(5, TimeUnit.SECONDS)).isTrue();

    Map<String, Object> fallback = new LinkedHashMap<>();
    fallback.put("fell", "back");

    Map<String, Object> theme = client.getJsonObject("theme", fallback);
    assertThat(theme).containsEntry("primary", "#101010");
    assertThat(theme).containsEntry("enabled", true);

    List<Object> features = client.getJsonArray("feature-list", Collections.<Object>emptyList());
    assertThat(features).containsExactly("alpha", "beta").inOrder();

    assertThat(client.getJsonObject("broken-json", fallback)).containsEntry("fell", "back");
    client.close();
  }

  @Test
  public void servesAJsonConfigThatNoCallerCanAlter() throws InterruptedException {
    ConfigDirectorClient client = client();
    CountDownLatch initialized = new CountDownLatch(1);
    client.initialize(null, initialized::countDown);
    assertThat(initialized.await(5, TimeUnit.SECONDS)).isTrue();

    Map<String, Object> theme = client.getJsonObject("theme", Collections.<String, Object>emptyMap());

    assertThrows(UnsupportedOperationException.class, () -> theme.put("primary", "#ffffff"));
    client.close();
  }

  @Test
  public void pausesAndResumesTheConnection() throws InterruptedException {
    ConfigDirectorClient client = client();
    CountDownLatch initialized = new CountDownLatch(1);
    client.initialize(null, initialized::countDown);
    assertThat(initialized.await(5, TimeUnit.SECONDS)).isTrue();

    client.pauseNetwork();
    assertThat(client.isReady()).isFalse();

    CountDownLatch resumed = new CountDownLatch(1);
    client.resumeNetwork(resumed::countDown);

    assertThat(resumed.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(client.isReady()).isTrue();
    client.close();
  }

  @Test
  public void initializesWithoutAContext() throws InterruptedException {
    ConfigDirectorClient client = client();
    CountDownLatch initialized = new CountDownLatch(1);

    client.initialize(null, initialized::countDown);

    assertThat(initialized.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(client.getContext()).isNull();
    assertThat(client.isReady()).isTrue();
    client.close();
  }

  @Test
  public void reEvaluatesAgainstAnUpdatedContext() throws InterruptedException {
    ConfigDirectorClient client = client();
    CountDownLatch initialized = new CountDownLatch(1);
    client.initialize(null, initialized::countDown);
    assertThat(initialized.await(5, TimeUnit.SECONDS)).isTrue();

    ConfigDirectorContext updated =
        ConfigDirectorContext.builder().id("user-456").trait("plan", "pro").build();
    CountDownLatch contextUpdated = new CountDownLatch(1);
    client.updateContext(updated, contextUpdated::countDown);

    assertThat(contextUpdated.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(client.getContext()).isEqualTo(updated);
    client.close();
  }

  @Test
  public void servesDefaultValuesBeforeItIsInitialized() {
    ConfigDirectorClient client = client();

    assertThat(client.isReady()).isFalse();
    assertThat(client.isInitializing()).isFalse();
    assertThat(client.getBoolean("dark-mode", false)).isFalse();
    assertThat(client.getString("welcome-message", "fallback")).isEqualTo("fallback");
    assertThat(client.getInt("max-items", 7)).isEqualTo(7);
    assertThat(client.getDouble("sample-rate", 1.5)).isEqualTo(1.5);
    client.close();
  }

  @Test
  public void rejectsABlankClientSdkKey() {
    assertThrows(
        ConfigDirectorValidationException.class,
        () -> new ConfigDirectorClient(RuntimeEnvironment.getApplication(), "   "));
  }

  @Test
  public void takesTheKeyOnItsOwn() {
    ConfigDirectorClient client =
        new ConfigDirectorClient(RuntimeEnvironment.getApplication(), "client-sdk-key");

    assertThat(client.isReady()).isFalse();
    client.close();
  }

  @Test
  public void closesWithTryWithResources() throws InterruptedException {
    CountDownLatch initialized = new CountDownLatch(1);
    ConfigDirectorClient closed;

    try (ConfigDirectorClient client = client()) {
      client.initialize(null, initialized::countDown);
      assertThat(initialized.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(client.isReady()).isTrue();
      closed = client;
    }

    assertThat(closed.isReady()).isFalse();
  }
}
