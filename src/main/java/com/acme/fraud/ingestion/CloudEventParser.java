package com.acme.fraud.ingestion;

import com.acme.fraud.evaluation.TransactionFacts;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Validates a CloudEvents 1.0 envelope and maps its data to {@link TransactionFacts}. */
public class CloudEventParser {

  public static final String EXPECTED_TYPE = "com.acme.payments.transaction-categorized.v1";

  private final JsonMapper mapper;
  private final JsonMapper canonical;

  public CloudEventParser(JsonMapper mapper) {
    this.mapper = mapper;
    this.canonical =
        JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
  }

  public ParsedEvent parse(String raw) {
    JsonNode root;
    try {
      root = mapper.readTree(raw);
    } catch (JacksonException e) {
      throw new InvalidEventException("Malformed JSON", e);
    }
    if (root == null || !root.isObject()) {
      throw new InvalidEventException("Event must be a JSON object");
    }
    if (!"1.0".equals(text(root, "specversion", "1.0"))) {
      throw new InvalidEventException("Unsupported specversion");
    }
    String id = required(root, "id");
    if (!EXPECTED_TYPE.equals(required(root, "type"))) {
      throw new InvalidEventException("Unexpected event type");
    }
    JsonNode data = root.path("data");
    if (!data.isObject()) {
      throw new InvalidEventException("data must be an object");
    }
    try {
      JsonNode location = data.path("location");
      TransactionFacts facts =
          new TransactionFacts(
              required(data, "transactionId"),
              required(data, "customerId"),
              required(data, "accountId"),
              new BigDecimal(required(data, "amount")),
              required(data, "currency"),
              text(data, "merchantCategory", null),
              text(data, "merchantId", null),
              required(data, "channel"),
              Instant.parse(required(data, "occurredAt")),
              text(location, "country", null),
              text(location, "city", null),
              text(data, "deviceId", null));
      return new ParsedEvent(id, facts, hash(data));
    } catch (IllegalArgumentException | java.time.DateTimeException e) {
      throw new InvalidEventException("Invalid transaction data: " + e.getMessage(), e);
    }
  }

  private static String required(JsonNode node, String field) {
    String v = text(node, field, null);
    if (v == null || v.isBlank()) {
      throw new InvalidEventException("Missing required field: " + field);
    }
    return v;
  }

  private static String text(JsonNode node, String field, String fallback) {
    JsonNode v = node.path(field);
    return v.isString() ? v.asString() : fallback;
  }

  private String hash(JsonNode data) {
    try {
      Object tree = canonical.treeToValue(data, Object.class);
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(canonical.writeValueAsString(tree).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
