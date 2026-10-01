package com.configdirector.sample.java;

import com.configdirector.ConfigDirectorClient;
import com.configdirector.testing.ConfigDirectorTesting;
import com.configdirector.testing.TestClient;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The application under test: the production one, with the client it builds replaced by a test
 * client from the SDK's testing tools. Robolectric runs it in place of {@link SampleApplication}
 * through the {@code @Config(application = ...)} on the test.
 */
public final class TestSampleApplication extends SampleApplication {

  static final Map<String, Object> SAMPLE_VALUES = sampleValues();

  final TestClient testClient = ConfigDirectorTesting.createTestClient(SAMPLE_VALUES);

  @Override
  ConfigDirectorClient createClient() {
    return testClient.getClient();
  }

  private static Map<String, Object> sampleValues() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("temporary-feature-flag", false);
    values.put("permanent-kill-switch", true);
    values.put("integer-config", 42);
    values.put("day-of-the-week-config", "Monday");
    values.put("json-value-config", Collections.singletonMap("theme", "dark"));
    return Collections.unmodifiableMap(values);
  }
}
