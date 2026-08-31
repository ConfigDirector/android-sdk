package com.configdirector.sample.java;

import com.configdirector.ConfigDirectorContext;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The identities the sample can evaluate configs against. Switching between them calls
 * {@code updateContext}, which reconnects and re-evaluates every config.
 */
enum SampleUser {
  CONFIGURED("Configured", ConfigDirectorContext.builder()
      .id("user-123")
      .name("Sam")
      .trait("plan", "free")
      .build()),

  PRO("Pro plan", ConfigDirectorContext.builder()
      .id("user-456")
      .name("Ada")
      .traits(proTraits())
      .build()),

  ANONYMOUS("Anonymous", ConfigDirectorContext.builder().anonymous(true).build());

  private final String label;
  private final ConfigDirectorContext context;

  SampleUser(String label, ConfigDirectorContext context) {
    this.label = label;
    this.context = context;
  }

  String label() {
    return label;
  }

  ConfigDirectorContext context() {
    return context;
  }

  private static Map<String, Object> proTraits() {
    Map<String, Object> traits = new LinkedHashMap<>();
    traits.put("plan", "pro");
    traits.put("seats", 12);
    traits.put("beta", true);
    return traits;
  }
}
