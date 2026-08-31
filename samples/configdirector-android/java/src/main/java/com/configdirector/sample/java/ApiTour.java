package com.configdirector.sample.java;

import com.configdirector.AndroidLogger;
import com.configdirector.ClientOptions;
import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConfigDirectorContext;
import com.configdirector.ConfigDirectorValidationException;
import com.configdirector.ConnectionMode;
import com.configdirector.ConnectionOptions;
import com.configdirector.LogLevel;
import com.configdirector.Metadata;

/**
 * Every call the SDK offers that the screen itself has no reason to make, gathered in one place.
 *
 * <p>The Kotlin tests cannot tell whether any of this is callable from Java: a suspend function, an
 * inline reified accessor or a member that arrives under a mangled name compiles for Kotlin and is
 * unusable here. If the Java surface breaks, this class stops compiling.
 */
final class ApiTour {

  private ApiTour() {}

  static void run(SampleLog log) {
    describeDefaults(log);
    describeContexts(log);
    describeOptions(log);
    describeKeyValidation(log);
  }

  private static void describeDefaults(SampleLog log) {
    ClientOptions defaults = ClientOptions.defaults();
    ConnectionOptions connection = ConnectionOptions.defaults();

    log.add(
        "defaults: mode="
            + connection.getMode()
            + " poll="
            + connection.getPollingIntervalMillis()
            + "ms timeout="
            + connection.getTimeoutMillis()
            + "ms baseUrl="
            + connection.getBaseUrl()
            + " pausesWhileBackgrounded="
            + connection.getPausesWhileBackgrounded());
    log.add(
        "defaults: appName="
            + defaults.getMetadata().getAppName()
            + " appVersion="
            + defaults.getMetadata().getAppVersion()
            + " logLevel="
            + defaults.getLogger().getLevel());
    log.add("empty metadata: appName=" + Metadata.empty().getAppName());
  }

  private static void describeContexts(SampleLog log) {
    ConfigDirectorContext empty = ConfigDirectorContext.empty();
    log.add(
        "empty context: id="
            + empty.getId()
            + " name="
            + empty.getName()
            + " traits="
            + empty.getTraits()
            + " anonymous="
            + empty.isAnonymous());

    ConfigDirectorContext anonymous = ConfigDirectorContext.builder().anonymous(true).build();
    log.add("anonymous context: anonymous=" + anonymous.isAnonymous());
  }

  private static void describeOptions(SampleLog log) {
    ClientOptions polling =
        ClientOptions.builder()
            .metadata("ConfigDirector Java Sample", "1.0")
            .connection(
                ConnectionOptions.builder()
                    .mode(ConnectionMode.POLLING)
                    .pollingIntervalMillis(30_000L)
                    .baseUrl("https://proxy.example.com")
                    .build())
            .logger(new AndroidLogger(LogLevel.WARN))
            .build();

    log.add(
        "polling options: mode="
            + polling.getConnection().getMode()
            + " every "
            + polling.getConnection().getPollingIntervalMillis()
            + "ms via "
            + polling.getConnection().getBaseUrl());

    // The key-only constructor, which every other call in the sample skips over.
    ConfigDirectorClient client = new ConfigDirectorClient("sample-client-sdk-key");
    log.add("one-argument constructor: ready=" + client.isReady());
    client.close();
  }

  private static void describeKeyValidation(SampleLog log) {
    try {
      new ConfigDirectorClient("   ");
      log.add("a blank SDK key was accepted, which it should not have been");
    } catch (ConfigDirectorValidationException rejected) {
      log.add("blank SDK key rejected: " + rejected.getMessage());
    }

    try {
      ConnectionOptions.builder().timeoutMillis(0L).build();
      log.add("a zero timeout was accepted, which it should not have been");
    } catch (ConfigDirectorValidationException rejected) {
      log.add("zero timeout rejected: " + rejected.getMessage());
    }
  }
}
