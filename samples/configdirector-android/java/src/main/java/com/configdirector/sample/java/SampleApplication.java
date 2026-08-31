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

  // The client rejects a blank key, so with none configured the sample runs on a stand-in that the
  // server will not recognize, and says so in the log on screen.
  private static final String PLACEHOLDER_SDK_KEY = "no-client-sdk-key-configured";

  private final SampleLog log = new SampleLog();

  private ConfigDirectorClient client;

  @Override
  public void onCreate() {
    super.onCreate();

    String clientSdkKey = BuildConfig.CLIENT_SDK_KEY;
    if (clientSdkKey.isEmpty()) {
      log.add(
          "No client SDK key configured. Add configdirector.clientSdkKey to local.properties; "
              + "until then every config is its default value.");
      clientSdkKey = PLACEHOLDER_SDK_KEY;
    }

    client =
        new ConfigDirectorClient(
            clientSdkKey,
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
