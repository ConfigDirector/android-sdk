package com.configdirector.sample.java;

import java.util.ArrayList;
import java.util.List;

/** What the sample has seen, written from the SDK's threads and read from the main thread. */
final class SampleLog {

  private static final int MAX_LINES = 200;

  private final List<String> lines = new ArrayList<>();

  synchronized void add(String line) {
    lines.add(line);
    if (lines.size() > MAX_LINES) {
      lines.remove(0);
    }
  }

  synchronized String text() {
    StringBuilder text = new StringBuilder();
    for (String line : lines) {
      text.append(line).append('\n');
    }
    return text.toString();
  }
}
