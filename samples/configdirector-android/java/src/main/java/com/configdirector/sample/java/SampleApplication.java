package com.configdirector.sample.java;

import android.app.Application;
import com.configdirector.ClientOptions;
import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConnectionMode;
import com.configdirector.ConnectionOptions;
import com.configdirector.Metadata;

/**
 * The client belongs to the application: one instance, created once, initialized during startup and
 * shared by every screen.
 *
 * <p>Nothing closes it. It lives as long as the process does, and Android reclaims everything when
 * the process ends — {@code onTerminate} only runs on an emulator. Call {@code close()} when an app
 * wants the client gone before that, on sign-out for instance. The Close client button on the
 * screen does exactly that.
 */
public final class SampleApplication extends Application {

  // The SDK talks to a stubbed transport for now, which serves the sample's configs whatever the
  // key is. It becomes a real key from the ConfigDirector dashboard once the transports land.
  private static final String SAMPLE_SDK_KEY = "sample-client-sdk-key";

  private final SampleLog log = new SampleLog();

  private ConfigDirectorClient client;

  @Override
  public void onCreate() {
    super.onCreate();

    client =
        new ConfigDirectorClient(
            SAMPLE_SDK_KEY,
            ClientOptions.builder()
                .metadata(new Metadata("ConfigDirector Java Sample", "1.0"))
                .connection(
                    ConnectionOptions.builder()
                        .mode(ConnectionMode.STREAMING)
                        .timeoutMillis(3_000L)
                        .pausesWhileBackgrounded(true)
                        .build())
                .logger(new SampleLogger(log))
                .build());

    client.initialize(
        SampleUser.CONFIGURED.context(),
        () -> log.add("initialize finished, ready=" + client.isReady()));
  }

  ConfigDirectorClient client() {
    return client;
  }

  SampleLog log() {
    return log;
  }
}
