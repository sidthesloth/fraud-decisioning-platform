package com.acme.fraud.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.fraud.FraudApplication;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Publish event -> decision persisted -> outbox relayed to fraud.evaluations/alerts/dlq. */
@SpringBootTest(classes = FraudApplication.class)
class IngestionEndToEndTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
  private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

  static {
    POSTGRES.start();
    KAFKA.start();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add("fraud.outbox.poll-interval-ms", () -> "100");
    r.add("fraud.ingestion.initial-backoff", () -> "10ms");
  }

  @Autowired JdbcClient jdbc;

  private static String event(String id, String txn, String amount) {
    return "{\"id\":\""
        + id
        + "\",\"type\":\"com.acme.payments.transaction-categorized.v1\","
        + "\"source\":\"//mock-producer/acme\",\"subject\":\""
        + txn
        + "\",\"time\":\"2026-10-17T18:00:00Z\","
        + "\"datacontenttype\":\"application/json\",\"data\":{\"transactionId\":\""
        + txn
        + "\",\"customerId\":\"cust_9\",\"accountId\":\"acc_4\",\"amount\":\""
        + amount
        + "\",\"currency\":\"ZAR\",\"merchantCategory\":\"MCC5999\",\"merchantId\":\"m1\","
        + "\"channel\":\"CARD_PRESENT\",\"occurredAt\":\"2026-10-17T17:59:00Z\","
        + "\"location\":{\"country\":\"ZA\",\"city\":\"Cape Town\"},\"deviceId\":\"dev_2\"}}";
  }

  private void send(String key, String value) throws Exception {
    Properties p = new Properties();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
      producer
          .send(new ProducerRecord<>("payments.transactions.categorized.v1", key, value))
          .get(10, TimeUnit.SECONDS);
    }
  }

  private List<ConsumerRecord<String, String>> await(String topic, int expected) {
    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    p.put(ConsumerConfig.GROUP_ID_CONFIG, "verify-" + topic + System.nanoTime());
    p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    List<ConsumerRecord<String, String>> got = new java.util.ArrayList<>();
    try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
      c.subscribe(List.of(topic));
      long end = System.currentTimeMillis() + 30_000;
      while (got.size() < expected && System.currentTimeMillis() < end) {
        c.poll(Duration.ofMillis(500)).forEach(got::add);
      }
    }
    return got;
  }

  @Test
  void flaggedEventFlowsToEvaluationAndAlertTopics_duplicateIgnored_poisonDeadLettered()
      throws Exception {
    jdbc.sql("INSERT INTO fraud_rules (rule_id) VALUES ('HIGH_VALUE')").update();
    jdbc.sql(
            "INSERT INTO fraud_rule_versions (rule_id, version, name, priority, condition, score,"
                + " reason_code, enabled) VALUES ('HIGH_VALUE', 1, 'High value', 1,"
                + " '{\"field\":\"amount\",\"op\":\"GT\",\"value\":\"5000.00\"}'::jsonb, 90,"
                + " 'HV', true)")
        .update();

    String flagged = event("evt_1", "txn_1", "6000.00");
    send("txn_1", flagged);
    send("txn_1", flagged);
    send("bad", "{not json");

    List<ConsumerRecord<String, String>> evals = await("fraud.evaluations.v1", 1);
    List<ConsumerRecord<String, String>> alerts = await("fraud.alerts.v1", 1);
    List<ConsumerRecord<String, String>> dlq = await("fraud.dlq.v1", 1);

    assertThat(evals).hasSize(1);
    assertThat(evals.get(0).value()).contains("\"outcome\": \"DECLINE\"").contains("HIGH_VALUE");
    assertThat(alerts).hasSize(1);
    assertThat(dlq).hasSize(1);
    assertThat(jdbc.sql("SELECT count(*) FROM decisions").query(Integer.class).single()).isOne();
  }
}
