package com.acme.fraud.bootstrap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes a fixed catalog of realistic categorized-transaction events (normal, high-value
 * cross-border, velocity burst, malformed) so the whole loop is visible. Active only with the
 * {@code mock-producer} profile.
 */
@Component
@Profile("mock-producer")
class MockProducer implements ApplicationRunner {

  static final String TOPIC = "payments.transactions.categorized.v1";
  private static final Logger LOG = LoggerFactory.getLogger(MockProducer.class);

  private final KafkaTemplate<String, String> kafka;

  MockProducer(KafkaTemplate<String, String> kafka) {
    this.kafka = kafka;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    for (String[] m : catalog(Instant.now(), UUID.randomUUID().toString().substring(0, 8))) {
      kafka.send(TOPIC, m[0], m[1]).get(10, TimeUnit.SECONDS);
    }
    LOG.info("Mock producer published its event catalog to {}", TOPIC);
  }

  /** Each entry is {key, json}. */
  static List<String[]> catalog(Instant now, String run) {
    List<String[]> out = new ArrayList<>();
    out.add(
        entry(
            run, "normal", "acc_n", "120.00", "CARD_PRESENT", "ZA", "dev_n", now.minusSeconds(60)));
    out.add(
        entry(
            run,
            "highvalue",
            "acc_h",
            "9500.00",
            "CARD_NOT_PRESENT",
            "NG",
            "dev_h",
            now.minusSeconds(50)));
    for (int i = 0; i < 6; i++) {
      out.add(
          entry(
              run,
              "velocity" + i,
              "acc_v",
              "80.00",
              "CARD_PRESENT",
              "ZA",
              "dev_v",
              now.minusSeconds(40 - i)));
    }
    out.add(new String[] {"malformed-" + run, "{not valid json"});
    return out;
  }

  private static String[] entry(
      String run,
      String name,
      String account,
      String amount,
      String channel,
      String country,
      String device,
      Instant at) {
    String txn = "txn_" + name + "_" + run;
    String json =
        """
        {"specversion":"1.0","id":"evt_%s","type":"com.acme.payments.transaction-categorized.v1",
         "source":"//mock-producer/acme","subject":"%s","time":"%s",
         "datacontenttype":"application/json",
         "data":{"transactionId":"%s","customerId":"cust_%s","accountId":"%s","amount":"%s",
          "currency":"ZAR","merchantCategory":"MCC5999","merchantId":"merch_77","channel":"%s",
          "occurredAt":"%s","location":{"country":"%s","city":"Cape Town"},"deviceId":"%s"}}
        """
            .formatted(txn, txn, at, txn, account, account, amount, channel, at, country, device);
    return new String[] {account, json};
  }
}
