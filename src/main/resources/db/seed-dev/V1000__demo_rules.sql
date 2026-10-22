-- Demo rules, loaded only with the dev profile (spring.flyway.locations in application-dev.yml).
INSERT INTO fraud_rules (rule_id) VALUES ('HIGH_VALUE'), ('CROSS_BORDER_CNP'), ('VELOCITY_1H');

INSERT INTO fraud_rule_versions
    (rule_id, version, name, description, priority, condition, score, reason_code, enabled)
VALUES
    ('HIGH_VALUE', 1, 'High value', 'Amount above 5000', 10,
     '{"field":"amount","op":"GT","value":"5000.00"}', 40, 'HIGH_VALUE', TRUE),
    ('CROSS_BORDER_CNP', 1, 'Cross-border card-not-present', 'Foreign card-not-present', 20,
     '{"all":[{"field":"channel","op":"EQ","value":"CARD_NOT_PRESENT"},{"field":"geo.country","op":"NOT_IN","value":["ZA","NA"]}]}',
     50, 'CROSS_BORDER_CNP', TRUE),
    ('VELOCITY_1H', 1, 'Velocity', 'More than 3 transactions in the last hour', 30,
     '{"field":"txn.lastHourCount","op":"GT","value":3}', 30, 'VELOCITY', TRUE);
