package com.configdirector.sample.java;

import com.configdirector.ConfigDirectorContext;

/**
 * The identities the sample can evaluate configs against. Switching between them calls {@code
 * updateContext}, which reconnects and re-evaluates every config.
 */
enum SampleUser {
  CONFIGURED("Configured", configuredContext()),

  BETA_TESTER(
      "Beta tester",
      ConfigDirectorContext.builder()
          .id("beta-tester")
          .name("Beta Tester")
          .trait("role", "beta")
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

  /** The identity from {@code local.properties}. With none set, configs are evaluated without a
   * context. */
  private static ConfigDirectorContext configuredContext() {
    ConfigDirectorContext.Builder builder =
        ConfigDirectorContext.builder()
            .id(emptyToNull(BuildConfig.USER_ID))
            .name(emptyToNull(BuildConfig.USER_NAME));

    String role = emptyToNull(BuildConfig.USER_ROLE);
    if (role != null) {
      builder.trait("role", role);
    }
    return builder.build();
  }

  private static String emptyToNull(String value) {
    return value.isEmpty() ? null : value;
  }
}
