package com.configdirector;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class OptionsJavaTest {

  @Test
  public void buildsConnectionOptionsFromTheBuilder() {
    ConnectionOptions connection =
        ConnectionOptions.builder()
            .mode(ConnectionMode.POLLING)
            .pollingIntervalMillis(120_000L)
            .timeoutMillis(5_000L)
            .baseUrl("https://proxy.example.com")
            .pausesWhileBackgrounded(false)
            .build();

    assertThat(connection.getMode()).isEqualTo(ConnectionMode.POLLING);
    assertThat(connection.getPollingIntervalMillis()).isEqualTo(120_000L);
    assertThat(connection.getTimeoutMillis()).isEqualTo(5_000L);
    assertThat(connection.getBaseUrl()).isEqualTo("https://proxy.example.com");
    assertThat(connection.getPausesWhileBackgrounded()).isFalse();
  }

  @Test
  public void connectsByStreamingByDefault() {
    ConnectionOptions connection = ConnectionOptions.defaults();

    assertThat(connection.getMode()).isEqualTo(ConnectionMode.STREAMING);
    assertThat(connection.getPollingIntervalMillis()).isEqualTo(60_000L);
    assertThat(connection.getTimeoutMillis()).isEqualTo(3_000L);
    assertThat(connection.getBaseUrl()).isNull();
    assertThat(connection.getPausesWhileBackgrounded()).isTrue();
  }

  @Test
  public void keepsAPollingIntervalBelowTheMinimumAsConfigured() {
    ConnectionOptions connection = ConnectionOptions.builder().pollingIntervalMillis(10_000L).build();

    assertThat(connection.getPollingIntervalMillis()).isEqualTo(10_000L);
  }

  @Test
  public void keepsAZeroPollingIntervalAsConfigured() {
    ConnectionOptions connection = ConnectionOptions.builder().pollingIntervalMillis(0L).build();

    assertThat(connection.getPollingIntervalMillis()).isEqualTo(0L);
  }

  @Test
  public void rejectsABaseUrlThatNamesNoHost() {
    ConnectionOptions.Builder builder = ConnectionOptions.builder().baseUrl("/configs");

    ConfigDirectorValidationException failure =
        assertThrows(ConfigDirectorValidationException.class, builder::build);

    assertThat(failure).hasMessageThat().contains("absolute and name a host");
  }

  @Test
  public void buildsOfflineConnectionOptionsWithValuesFromTheBuilder() {
    ConnectionOptions connection =
        ConnectionOptions.builder()
            .mode(ConnectionMode.OFFLINE)
            .values(Map.of("new-checkout", true, "max-items", 20))
            .build();

    assertThat(connection.getMode()).isEqualTo(ConnectionMode.OFFLINE);
    assertThat(connection.getValues()).containsExactly("new-checkout", true, "max-items", 20);
  }

  @Test
  public void rejectsAnOfflineValueThatIsNull() {
    Map<String, Object> values = new HashMap<>();
    values.put("new-checkout", null);
    ConnectionOptions.Builder builder =
        ConnectionOptions.builder().mode(ConnectionMode.OFFLINE).values(values);

    ConfigDirectorValidationException failure =
        assertThrows(ConfigDirectorValidationException.class, builder::build);

    assertThat(failure).hasMessageThat().contains("Invalid value for 'new-checkout'");
  }

  @Test
  public void buildsClientOptionsFromTheBuilder() {
    ClientOptions options =
        ClientOptions.builder()
            .metadata("Checkout", "4.2.0")
            .connection(ConnectionOptions.builder().mode(ConnectionMode.POLLING).build())
            .caching(CachingOptions.builder().enabled(false).build())
            .logger(new AndroidLogger(LogLevel.DEBUG))
            .build();

    assertThat(options.getMetadata()).isEqualTo(new Metadata("Checkout", "4.2.0"));
    assertThat(options.getConnection().getMode()).isEqualTo(ConnectionMode.POLLING);
    assertThat(options.getCaching().getEnabled()).isFalse();
    assertThat(options.getLogger().getLevel()).isEqualTo(LogLevel.DEBUG);
  }

  @Test
  public void carriesDefaultMetadataConnectionCachingAndLogger() {
    ClientOptions options = ClientOptions.defaults();

    assertThat(options.getMetadata()).isEqualTo(Metadata.empty());
    assertThat(options.getConnection()).isEqualTo(ConnectionOptions.defaults());
    assertThat(options.getCaching().getEnabled()).isTrue();
    assertThat(options.getCaching().getProvider()).isNull();
    assertThat(options.getLogger().getLevel()).isEqualTo(LogLevel.WARN);
  }

  @Test
  public void keepsValuesOnTheDeviceByDefault() {
    CachingOptions caching = CachingOptions.defaults();

    assertThat(caching.getEnabled()).isTrue();
    assertThat(caching.getProvider()).isNull();
  }

  @Test
  public void buildsMetadataWithBothFieldsOptional() {
    assertThat(new Metadata("Checkout").getAppVersion()).isNull();
    assertThat(new Metadata().getAppName()).isNull();
  }
}
