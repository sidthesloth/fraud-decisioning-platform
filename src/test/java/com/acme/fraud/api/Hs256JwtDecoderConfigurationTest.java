package com.acme.fraud.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class Hs256JwtDecoderConfigurationTest {

  private static final String PRIVATE_SECRET = "private-secret-private-secret-0123456789";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Hs256JwtDecoderConfiguration.class);

  @Test
  void noDecoderWhenSecretIsAbsent() {
    runner.run(ctx -> assertThat(ctx).doesNotHaveBean(JwtDecoder.class));
  }

  @Test
  void noDecoderWhenSecretIsEmpty() {
    runner
        .withPropertyValues("fraud.security.jwt-secret=")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(JwtDecoder.class));
  }

  @Test
  void decoderWhenPrivateSecretIsSet() {
    runner
        .withPropertyValues("fraud.security.jwt-secret=" + PRIVATE_SECRET)
        .run(ctx -> assertThat(ctx).hasSingleBean(JwtDecoder.class));
  }

  @Test
  void rejectsShortSecret() {
    runner
        .withPropertyValues("fraud.security.jwt-secret=too-short")
        .run(
            ctx ->
                assertThat(ctx.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("at least 32 bytes"));
  }

  @Test
  void rejectsPublicDevSecretOutsideDevProfile() {
    runner
        .withPropertyValues("fraud.security.jwt-secret=" + Hs256JwtDecoderConfiguration.DEV_SECRET)
        .run(
            ctx ->
                assertThat(ctx.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("only allowed with the 'dev' profile"));
  }

  @Test
  void allowsPublicDevSecretInDevProfile() {
    runner
        .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
        .withPropertyValues("fraud.security.jwt-secret=" + Hs256JwtDecoderConfiguration.DEV_SECRET)
        .run(ctx -> assertThat(ctx).hasSingleBean(JwtDecoder.class));
  }
}
