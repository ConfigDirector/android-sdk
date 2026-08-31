package com.configdirector;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class ConfigDirectorContextJavaTest {

  @Test
  public void buildsAContextFromTheBuilder() {
    ConfigDirectorContext context =
        ConfigDirectorContext.builder()
            .id("user-123")
            .name("Ada")
            .trait("plan", "pro")
            .trait("seats", 12)
            .anonymous(true)
            .build();

    assertThat(context.getId()).isEqualTo("user-123");
    assertThat(context.getName()).isEqualTo("Ada");
    assertThat(context.getTraits()).containsExactly("plan", "pro", "seats", 12);
    assertThat(context.isAnonymous()).isTrue();
  }

  @Test
  public void carriesNothingWhenEmpty() {
    ConfigDirectorContext context = ConfigDirectorContext.empty();

    assertThat(context.getId()).isNull();
    assertThat(context.getTraits()).isNull();
    assertThat(context.isAnonymous()).isFalse();
  }

  @Test
  public void setsEveryTraitAtOnce() {
    Map<String, Object> traits = new LinkedHashMap<>();
    traits.put("beta", true);
    traits.put("regions", Arrays.asList("us-east", "eu-west"));

    ConfigDirectorContext context = ConfigDirectorContext.builder().traits(traits).build();

    assertThat(context.getTraits()).hasSize(2);
  }

  @Test
  public void copiesTraitsSoThatMutatingTheSourceMapLeavesTheContextAlone() {
    Map<String, Object> traits = new LinkedHashMap<>();
    traits.put("plan", "pro");
    ConfigDirectorContext context = ConfigDirectorContext.builder().traits(traits).build();

    traits.put("plan", "free");

    assertThat(context.getTraits()).containsExactly("plan", "pro");
  }

  @Test
  public void handsOutTraitsThatCannotBeModified() {
    ConfigDirectorContext context = ConfigDirectorContext.builder().trait("plan", "pro").build();

    Map<String, Object> traits = context.getTraits();

    assertThrows(UnsupportedOperationException.class, () -> traits.put("plan", "free"));
  }

  @Test
  public void equalsEveryContextCarryingTheSameValues() {
    ConfigDirectorContext context = ConfigDirectorContext.builder().id("user-123").build();
    ConfigDirectorContext same = ConfigDirectorContext.builder().id("user-123").build();

    assertThat(context).isEqualTo(same);
    assertThat(context.hashCode()).isEqualTo(same.hashCode());
  }

  @Test
  public void rejectsATraitThatNoTargetingRuleCouldMatch() {
    ConfigDirectorContext.Builder builder =
        ConfigDirectorContext.builder().trait("joined", new Date(0));

    ConfigDirectorValidationException failure =
        assertThrows(ConfigDirectorValidationException.class, builder::build);

    assertThat(failure).hasMessageThat().contains("Invalid trait 'joined'");
  }

  @Test
  public void rejectsAMapInsideATraitThatIsNotKeyedByString() {
    ConfigDirectorContext.Builder builder =
        ConfigDirectorContext.builder().trait("seats", Collections.singletonMap(1, "one"));

    ConfigDirectorValidationException failure =
        assertThrows(ConfigDirectorValidationException.class, builder::build);

    assertThat(failure).hasMessageThat().contains("must be keyed by String");
  }

  @Test
  public void rejectsANullTraitKey() {
    ConfigDirectorContext.Builder builder = ConfigDirectorContext.builder();

    assertThrows(NullPointerException.class, () -> builder.trait(null, "pro"));
  }

  @Test
  public void acceptsANullTraitValueAndANullId() {
    ConfigDirectorContext context =
        ConfigDirectorContext.builder().id(null).name(null).trait("absent", null).build();

    assertThat(context.getId()).isNull();
    assertThat(context.getTraits()).containsExactly("absent", null);
  }
}
