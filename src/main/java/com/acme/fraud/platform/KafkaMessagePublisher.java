package com.acme.fraud.platform;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;

/** Blocks until the broker acknowledges, so the relay marks rows published only when durable. */
public class KafkaMessagePublisher implements OutboxRelay.MessagePublisher {

  private final KafkaTemplate<String, String> kafka;
  private final long timeoutSeconds;

  public KafkaMessagePublisher(KafkaTemplate<String, String> kafka, long timeoutSeconds) {
    this.kafka = kafka;
    this.timeoutSeconds = timeoutSeconds;
  }

  @Override
  public void publish(String topic, String key, String payload) {
    try {
      kafka.send(topic, key, payload).get(timeoutSeconds, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted publishing to " + topic, e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("Publish to " + topic + " failed", e);
    }
  }
}
