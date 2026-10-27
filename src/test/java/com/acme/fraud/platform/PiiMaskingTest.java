package com.acme.fraud.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PiiMaskingTest {

  @Test
  void keepsPrefixAndLastCharacter() {
    assertThat(PiiMasking.maskId("cust_12345")).isEqualTo("cust_***5");
  }

  @Test
  void fullyMasksShortRemainders() {
    assertThat(PiiMasking.maskId("cust_9")).isEqualTo("cust_***");
    assertThat(PiiMasking.maskId("cust_98")).isEqualTo("cust_***");
    assertThat(PiiMasking.maskId("ab")).isEqualTo("***");
  }

  @Test
  void masksIdsWithoutPrefix() {
    assertThat(PiiMasking.maskId("123456")).isEqualTo("***6");
  }

  @Test
  void neverContainsTheMiddleOfTheOriginal() {
    assertThat(PiiMasking.maskId("cust_abcdefgh")).doesNotContain("abcdefg").isEqualTo("cust_***h");
  }

  @Test
  void passesThroughNullAndBlank() {
    assertThat(PiiMasking.maskId(null)).isNull();
    assertThat(PiiMasking.maskId("  ")).isEqualTo("  ");
  }
}
