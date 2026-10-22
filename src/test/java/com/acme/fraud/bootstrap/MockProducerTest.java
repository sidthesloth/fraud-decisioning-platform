package com.acme.fraud.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.fraud.ingestion.CloudEventParser;
import com.acme.fraud.ingestion.InvalidEventException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MockProducerTest {

  @Test
  void catalogParsesExceptForTheMalformedEvent() {
    CloudEventParser parser = new CloudEventParser(JsonMapper.builder().build());
    List<String[]> catalog = MockProducer.catalog(Instant.now(), "t1");
    int invalid = 0;
    for (String[] m : catalog) {
      try {
        parser.parse(m[1]);
      } catch (InvalidEventException e) {
        invalid++;
      }
    }
    assertThat(catalog).hasSize(9);
    assertThat(invalid).isOne();
  }
}
