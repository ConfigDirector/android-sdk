package com.configdirector.sample.java;

import android.util.Log;
import com.configdirector.ConfigDirectorLogger;
import com.configdirector.LogLevel;

/**
 * Routes the SDK's logs into logcat and into the sample's own log. The SDK writes from whatever
 * thread it is on, which is why {@link SampleLog} is synchronized.
 */
final class SampleLogger implements ConfigDirectorLogger {

  private final SampleLog log;

  SampleLogger(SampleLog log) {
    this.log = log;
  }

  @Override
  public LogLevel getLevel() {
    return LogLevel.DEBUG;
  }

  @Override
  public void log(LogLevel level, String message, Throwable error) {
    Log.println(priorityOf(level), "ConfigDirectorSample", message);
    if (error != null) {
      Log.w("ConfigDirectorSample", message, error);
    }
    log.add("[" + level + "] " + message);
  }

  private static int priorityOf(LogLevel level) {
    switch (level) {
      case ERROR:
        return Log.ERROR;
      case WARN:
        return Log.WARN;
      case INFO:
        return Log.INFO;
      default:
        return Log.DEBUG;
    }
  }
}
