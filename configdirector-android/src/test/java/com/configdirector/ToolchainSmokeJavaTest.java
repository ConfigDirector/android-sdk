package com.configdirector;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

public class ToolchainSmokeJavaTest {

  @Test
  public void carriesTheMessage() {
    ConfigDirectorException exception = new ConfigDirectorException("boom", null);

    assertThat(exception).hasMessageThat().isEqualTo("boom");
  }
}
