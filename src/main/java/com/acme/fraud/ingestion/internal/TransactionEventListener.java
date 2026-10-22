package com.acme.fraud.ingestion.internal;

import com.acme.fraud.ingestion.ResilientEventHandler;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Kafka entry point. Acknowledges only after the handler settled the message (processed, duplicate
 * or dead-lettered); any exception leaves the offset uncommitted for redelivery.
 */
public class TransactionEventListener {

  public static final String INPUT_TOPIC = "payments.transactions.categorized.v1";

  private final ResilientEventHandler handler;

  public TransactionEventListener(ResilientEventHandler handler) {
    this.handler = handler;
  }

  @KafkaListener(topics = INPUT_TOPIC, groupId = "${fraud.ingestion.group-id:fraud-decisioning}")
  public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
    if (record.key() != null) {
      MDC.put("eventId", record.key());
    }
    Header traceparent = record.headers().lastHeader("traceparent");
    if (traceparent != null) {
      String[] parts =
          new String(traceparent.value(), java.nio.charset.StandardCharsets.UTF_8).split("-");
      if (parts.length >= 3) {
        MDC.put("traceId", parts[1]);
        MDC.put("spanId", parts[2]);
      }
    }
    try {
      handler.handle(record.value());
      ack.acknowledge();
    } finally {
      MDC.remove("eventId");
      MDC.remove("traceId");
      MDC.remove("spanId");
    }
  }
}
