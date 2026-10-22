package com.acme.fraud.ruledomain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Parses the JSON condition DSL into a {@link Condition}; fails only with RuleDslException. */
public final class ConditionParser {

  static final int MAX_DEPTH = 32;

  private static final Set<String> LEAF_KEYS = Set.of("field", "op", "value");

  private final ObjectMapper mapper;

  public ConditionParser() {
    this(JsonMapper.builder().build());
  }

  public ConditionParser(ObjectMapper mapper) {
    if (mapper == null) {
      throw new IllegalArgumentException("mapper must not be null");
    }
    this.mapper = mapper;
  }

  public Condition parse(String json) {
    if (json == null || json.isBlank()) {
      throw new RuleDslException("Condition JSON must not be empty");
    }
    JsonNode root;
    try {
      root = mapper.readTree(json);
    } catch (JacksonException e) {
      throw new RuleDslException("Malformed condition JSON", e);
    }
    return parse(root);
  }

  public Condition parse(JsonNode node) {
    try {
      return parseNode(node, "$", 1);
    } catch (RuleDslException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new RuleDslException("Invalid condition", e);
    }
  }

  private Condition parseNode(JsonNode node, String path, int depth) {
    if (depth > MAX_DEPTH) {
      throw new RuleDslException(path + ": nesting deeper than " + MAX_DEPTH);
    }
    if (node == null || !node.isObject()) {
      throw new RuleDslException(path + ": condition must be a JSON object");
    }
    boolean hasAll = node.has("all");
    boolean hasAny = node.has("any");
    boolean hasLeaf = node.has("field") || node.has("op") || node.has("value");
    int kinds = (hasAll ? 1 : 0) + (hasAny ? 1 : 0) + (hasLeaf ? 1 : 0);
    if (kinds != 1) {
      throw new RuleDslException(
          path + ": exactly one of 'all', 'any' or a field/op leaf is required");
    }
    if (node.size() != 1 && !hasLeaf) {
      throw new RuleDslException(path + ": unexpected properties");
    }
    if (hasAll) {
      return new Condition.All(parseChildren(node.get("all"), path + ".all", depth));
    }
    if (hasAny) {
      return new Condition.Any(parseChildren(node.get("any"), path + ".any", depth));
    }
    return parseLeaf(node, path);
  }

  private List<Condition> parseChildren(JsonNode array, String path, int depth) {
    if (array == null || !array.isArray()) {
      throw new RuleDslException(path + ": must be an array");
    }
    if (array.size() == 0) {
      throw new RuleDslException(path + ": must not be empty");
    }
    List<Condition> children = new ArrayList<>(array.size());
    int index = 0;
    for (JsonNode child : array) {
      children.add(parseNode(child, path + "[" + index + "]", depth + 1));
      index++;
    }
    return children;
  }

  private Condition parseLeaf(JsonNode node, String path) {
    for (String name : node.propertyNames()) {
      if (!LEAF_KEYS.contains(name)) {
        throw new RuleDslException(path + ": unexpected property");
      }
    }
    JsonNode field = node.get("field");
    if (field == null || !field.isString()) {
      throw new RuleDslException(path + ".field: required string");
    }
    JsonNode op = node.get("op");
    if (op == null || !op.isString()) {
      throw new RuleDslException(path + ".op: required string");
    }
    Operator operator;
    try {
      operator = Operator.fromJson(op.stringValue());
    } catch (RuleDslException e) {
      throw new RuleDslException(path + ".op: " + e.getMessage(), e);
    }
    JsonNode valueNode = node.get("value");
    Object value = null;
    if (operator != Operator.EXISTS) {
      if (valueNode == null) {
        throw new RuleDslException(path + ".value: required");
      }
      value = toValue(valueNode, path + ".value", true);
    } else if (valueNode != null) {
      throw new RuleDslException(path + ".value: not allowed for EXISTS");
    }
    try {
      return new Condition.Leaf(field.stringValue(), operator, value);
    } catch (RuleDslException e) {
      throw new RuleDslException(path + ": " + e.getMessage(), e);
    }
  }

  private Object toValue(JsonNode node, String path, boolean allowArray) {
    if (node.isString()) {
      return node.stringValue();
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isNumber()) {
      return node.decimalValue();
    }
    if (node.isArray() && allowArray) {
      List<Object> items = new ArrayList<>(node.size());
      int index = 0;
      for (JsonNode element : node) {
        items.add(toValue(element, path + "[" + index + "]", false));
        index++;
      }
      return items;
    }
    throw new RuleDslException(path + ": unsupported value type");
  }
}
