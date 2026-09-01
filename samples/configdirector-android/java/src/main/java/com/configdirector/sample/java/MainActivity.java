package com.configdirector.sample.java;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import com.configdirector.ClientEvent;
import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConfigDirectorContext;
import com.configdirector.ConfigEvaluation;
import com.configdirector.Subscription;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exercises the whole Java surface of the SDK: every accessor, every watch, both listener kinds,
 * and the callback forms of initialize and updateContext.
 */
public final class MainActivity extends Activity {

  private final List<Subscription> subscriptions = new ArrayList<>();
  private final Map<String, String> watchedValues = new LinkedHashMap<>();

  private ConfigDirectorClient client;
  private SampleLog log;
  private TextView status;
  private TextView watchedConfigs;
  private TextView logView;
  private ScrollView logScroll;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    SampleApplication application = (SampleApplication) getApplication();
    client = application.client();
    log = application.log();

    status = findViewById(R.id.status);
    watchedConfigs = findViewById(R.id.watched_configs);
    logView = findViewById(R.id.log);
    logScroll = findViewById(R.id.log_scroll);

    wireUserButtons();
    findViewById(R.id.read_every_config).setOnClickListener(view -> readEveryConfig());
    findViewById(R.id.exercise_every_api).setOnClickListener(view -> exerciseEveryApi());
    findViewById(R.id.close_client).setOnClickListener(view -> closeClient());

    watchEveryConfig();
    listen();
    render();
  }

  @Override
  protected void onDestroy() {
    // The watches belong to this screen; the client belongs to the application.
    for (Subscription subscription : subscriptions) {
      subscription.close();
    }
    subscriptions.clear();
    super.onDestroy();
  }

  /**
   * A watch is handed the config's current value straight away, and then every value it changes to.
   * The callbacks arrive on the main thread, so they can touch views directly.
   */
  private void watchEveryConfig() {
    subscriptions.add(
        client.watchBoolean(
            "temporary-feature-flag", true, value -> onWatched("temporary-feature-flag", value)));
    subscriptions.add(
        client.watchBoolean(
            "permanent-kill-switch", false, value -> onWatched("permanent-kill-switch", value)));
    subscriptions.add(
        client.watchString(
            "day-of-the-week-config",
            "Friday",
            value -> onWatched("day-of-the-week-config", value)));
    subscriptions.add(
        client.watchInt("integer-config", 10, value -> onWatched("integer-config", value)));
    subscriptions.add(
        client.watchDouble(
            "integer-config", 0.0, value -> onWatched("integer-config as a double", value)));
    subscriptions.add(
        client.watchJsonObject(
            "json-value-config",
            Collections.<String, Object>emptyMap(),
            value -> onWatched("json-value-config", value)));
  }

  private void listen() {
    subscriptions.add(
        client.addEventListener(
            event -> {
              if (event instanceof ClientEvent.Ready) {
                log.add("ready after " + ((ClientEvent.Ready) event).getReason().getDescription());
              } else if (event instanceof ClientEvent.ConfigsUpdated) {
                log.add("configs updated: " + ((ClientEvent.ConfigsUpdated) event).getKeys());
              } else if (event instanceof ClientEvent.ContextUpdated) {
                ConfigDirectorContext updated = ((ClientEvent.ContextUpdated) event).getContext();
                log.add("context now " + (updated == null ? "unset" : updated.getId()));
              }
              render();
            }));

    subscriptions.add(client.addEvaluationListener(this::onEvaluation));
  }

  private void onEvaluation(ConfigEvaluation evaluation) {
    ConfigDirectorContext context = evaluation.getContext();
    String who = context == null ? "no context" : String.valueOf(context.getId());

    if (evaluation.isDefaultValue()) {
      log.add(
          "'"
              + evaluation.getKey()
              + "' fell back to "
              + evaluation.getValue()
              + " ("
              + evaluation.getReason().getWireName()
              + ", "
              + who
              + ")");
    } else {
      log.add(
          "'"
              + evaluation.getKey()
              + "' served "
              + evaluation.getValue()
              + " (valueId "
              + evaluation.getValueId()
              + ", "
              + who
              + ")");
    }
  }

  private void wireUserButtons() {
    bindUser(R.id.user_configured, SampleUser.CONFIGURED);
    bindUser(R.id.user_beta_tester, SampleUser.BETA_TESTER);
    bindUser(R.id.user_anonymous, SampleUser.ANONYMOUS);
  }

  private void bindUser(int buttonId, SampleUser user) {
    Button button = findViewById(buttonId);
    button.setOnClickListener(
        view -> {
          log.add("switching to " + user.label());
          render();
          client.updateContext(
              user.context(), () -> log.add("updateContext finished, ready=" + client.isReady()));
        });
  }

  /** Reads every config the sample knows about, including ones that fall back to their default. */
  private void readEveryConfig() {
    log.add("temporary-feature-flag=" + client.getBoolean("temporary-feature-flag", true));
    log.add("permanent-kill-switch=" + client.getBoolean("permanent-kill-switch", false));
    log.add("integer-config=" + client.getInt("integer-config", 10));
    log.add("day-of-the-week-config=" + client.getString("day-of-the-week-config", "Friday"));
    log.add("json-value-config=" + client.getString("json-value-config", "{}"));
    log.add("integer-config as a double=" + client.getDouble("integer-config", 0.0));
    // A JSON config also reads as a map, whose values are String, Number, Boolean, List, Map or
    // null. Reading it as a string above serves the same document unparsed.
    log.add(
        "json-value-config as a map="
            + client.getJsonObject("json-value-config", Collections.<String, Object>emptyMap()));

    // No config carries this key.
    log.add("no-such-config=" + client.getString("no-such-config", "fallback"));
    // Held as an integer, so it cannot be read as a boolean.
    log.add("integer-config as boolean=" + client.getBoolean("integer-config", true));

    render();
  }

  private void exerciseEveryApi() {
    ApiTour.run(this, log);
    render();
  }

  private void closeClient() {
    client.close();
    log.add("client closed, ready=" + client.isReady());
    render();
  }

  private void onWatched(String key, Object value) {
    watchedValues.put(key, String.valueOf(value));
    render();
  }

  private void render() {
    ConfigDirectorContext context = client.getContext();
    status.setText(
        "ready="
            + client.isReady()
            + "  initializing="
            + client.isInitializing()
            + "  context="
            + (context == null ? "none" : context.getName() + " " + context.getTraits()));

    StringBuilder watched = new StringBuilder();
    for (Map.Entry<String, String> entry : watchedValues.entrySet()) {
      watched.append(entry.getKey()).append(" = ").append(entry.getValue()).append('\n');
    }
    watchedConfigs.setText(watched.toString().trim());

    logView.setText(log.text());
    logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
  }
}
