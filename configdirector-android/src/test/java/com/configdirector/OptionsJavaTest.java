package com.configdirector;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class OptionsJavaTest {

  @Test
  public void buildsConnectionOptionsFromTheBuilder() {
    ConnectionOptions connection =
        ConnectionOptions.builder()
            .mode(ConnectionMode.POLLING)
            .pollingIntervalMillis(30_000L)
            .timeoutMillis(5_000L)
            .baseUrl("https://proxy.example.com")
            .pausesWhileBackgrounded(false)
            .build();

    assertThat(connection.getMode()).isEqualTo(ConnectionMode.POLLING);
    assertThat(connection.getPollingIntervalMillis()).isEqualTo(30_000L);
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
  public void rejectsAPollingIntervalThatWouldNeverComeRound() {
    ConnectionOptions.Builder builder = ConnectionOptions.builder().pollingIntervalMillis(0L);

    ConfigDirectorValidationException failure =
        assertThrows(ConfigDirectorValidationException.class, builder::build);

    assertThat(failure).hasMessageThat().contains("pollingIntervalMillis '0'");
  }

  @Test
  public void rejectsABaseUrlThatNamesNoHost() {
    ConnectionOptions.Builder builder = ConnectionOptions.builder().baseUrl("/configs");

    ConfigDirectorValidationException failure =
        assertThrows(ConfigDirectorValidationException.class, builder::build);

    assertThat(failure).hasMessageThat().contains("absolute and name a host");
  }

  @Test
  public void buildsClientOptionsFromTheBuilder() {
    ClientOptions options =
        ClientOptions.builder()
            .metadata("Checkout", "4.2.0")
            .connection(ConnectionOptions.builder().mode(ConnectionMode.ONE_TIME).build())
            .logger(new AndroidLogger(LogLevel.DEBUG))
            .build();

    assertThat(options.getMetadata()).isEqualTo(new Metadata("Checkout", "4.2.0"));
    assertThat(options.getConnection().getMode()).isEqualTo(ConnectionMode.ONE_TIME);
    assertThat(options.getLogger().getLevel()).isEqualTo(LogLevel.DEBUG);
  }

  @Test
  public void carriesDefaultMetadataConnectionAndLogger() {
    ClientOptions options = ClientOptions.defaults();

    assertThat(options.getMetadata()).isEqualTo(Metadata.empty());
    assertThat(options.getConnection()).isEqualTo(ConnectionOptions.defaults());
    assertThat(options.getLogger().getLevel()).isEqualTo(LogLevel.WARN);
  }

  @Test
  public void buildsMetadataWithBothFieldsOptional() {
    assertThat(new Metadata("Checkout").getAppVersion()).isNull();
    assertThat(new Metadata().getAppName()).isNull();
  }
}
