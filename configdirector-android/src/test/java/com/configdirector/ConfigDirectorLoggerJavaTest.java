package com.configdirector;

import static com.google.common.truth.Truth.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ConfigDirectorLoggerJavaTest {

  private static final class JavaLogger implements ConfigDirectorLogger {

    private final List<String> messages = new ArrayList<>();

    @Override
    public LogLevel getLevel() {
      return LogLevel.DEBUG;
    }

    @Override
    public void log(LogLevel level, String message, Throwable error) {
      messages.add(level + ": " + message + (error == null ? "" : " (" + error.getMessage() + ")"));
    }
  }

  @Test
  public void routesSdkLogsIntoAJavaLogger() {
    JavaLogger logger = new JavaLogger();

    ClientOptions options = ClientOptions.builder().logger(logger).build();
    options.getLogger().log(LogLevel.WARN, "retrying", new RuntimeException("boom"));

    assertThat(logger.messages).containsExactly("WARN: retrying (boom)");
  }

  @Test
  public void warnsByDefaultAndTakesALevel() {
    assertThat(new AndroidLogger().getLevel()).isEqualTo(LogLevel.WARN);
    assertThat(new AndroidLogger(LogLevel.OFF).getLevel()).isEqualTo(LogLevel.OFF);
  }
}
