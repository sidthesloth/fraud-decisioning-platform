package com.acme.fraud.platform;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class PlatformConfiguration {

  @Bean
  public CorrelationIdFilter correlationIdFilter() {
    return new CorrelationIdFilter();
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  OutboxRelay outboxRelay(
      JdbcClient jdbc,
      KafkaTemplate<String, String> kafka,
      @Value("${fraud.outbox.batch-size:100}") int batchSize,
      @Value("${fraud.outbox.publish-timeout-seconds:10}") long timeoutSeconds) {
    return new OutboxRelay(jdbc, new KafkaMessagePublisher(kafka, timeoutSeconds), batchSize);
  }

  @Bean
  OutboxRelayJob outboxRelayJob(OutboxRelay relay) {
    return new OutboxRelayJob(relay);
  }

  /** Drains the outbox until empty or a publish fails. */
  public static class OutboxRelayJob {
    private final OutboxRelay relay;

    OutboxRelayJob(OutboxRelay relay) {
      this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${fraud.outbox.poll-interval-ms:500}")
    public void run() {
      while (relay.relayBatch() > 0) {
        // keep draining
      }
    }
  }
}
