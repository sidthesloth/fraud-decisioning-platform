-- Core schema. Money = NUMERIC(19,4) + currency code; timestamps = timestamptz (UTC).

-- Inbox: idempotent consumption by CloudEvents id.
CREATE TABLE processed_events (
    event_id     TEXT        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Rules: append-only versions, never updated in place except the lifecycle flag.
CREATE TABLE fraud_rules (
    rule_id    TEXT        PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE fraud_rule_versions (
    rule_id        TEXT        NOT NULL REFERENCES fraud_rules (rule_id),
    version        INTEGER     NOT NULL CHECK (version >= 1),
    name           TEXT        NOT NULL,
    description    TEXT        NOT NULL DEFAULT '',
    priority       INTEGER     NOT NULL,
    condition      JSONB       NOT NULL,
    score          INTEGER     NOT NULL CHECK (score >= 0),
    reason_code    TEXT        NOT NULL,
    enabled        BOOLEAN     NOT NULL DEFAULT FALSE,
    effective_from TIMESTAMPTZ,
    effective_to   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version    BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (rule_id, version),
    CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from)
);
CREATE INDEX idx_rule_versions_enabled ON fraud_rule_versions (enabled, priority);

-- Decisions: matched-rule snapshots are stored WITH the decision and never rewritten.
CREATE TABLE decisions (
    decision_id         UUID          PRIMARY KEY,
    transaction_id      TEXT          NOT NULL UNIQUE,
    customer_id         TEXT          NOT NULL,
    outcome             TEXT          NOT NULL CHECK (outcome IN ('APPROVE', 'REVIEW', 'DECLINE')),
    score               INTEGER       NOT NULL,
    enrichment_degraded BOOLEAN       NOT NULL,
    input_hash          CHAR(64)      NOT NULL,
    engine_version      TEXT          NOT NULL,
    evaluated_at        TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_decisions_customer ON decisions (customer_id, evaluated_at);
CREATE INDEX idx_decisions_outcome ON decisions (outcome, evaluated_at);

CREATE TABLE decision_matched_rules (
    decision_id  UUID    NOT NULL REFERENCES decisions (decision_id),
    rule_id      TEXT    NOT NULL,
    rule_version INTEGER NOT NULL,
    contribution INTEGER NOT NULL,
    reason_code  TEXT    NOT NULL,
    PRIMARY KEY (decision_id, rule_id, rule_version)
);

CREATE TABLE decision_skipped_rules (
    decision_id  UUID    NOT NULL REFERENCES decisions (decision_id),
    rule_id      TEXT    NOT NULL,
    rule_version INTEGER NOT NULL,
    reason       TEXT    NOT NULL,
    PRIMARY KEY (decision_id, rule_id, rule_version)
);

-- Alerts: created only for REVIEW/DECLINE; optimistic locking via row_version.
CREATE TABLE alerts (
    alert_id    UUID        PRIMARY KEY,
    decision_id UUID        NOT NULL UNIQUE REFERENCES decisions (decision_id),
    severity    TEXT        NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    status      TEXT        NOT NULL
        CHECK (status IN ('OPEN', 'IN_REVIEW', 'CONFIRMED_FRAUD', 'FALSE_POSITIVE', 'CLOSED')),
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    row_version BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX idx_alerts_status_severity ON alerts (status, severity);

CREATE TABLE alert_transitions (
    id          BIGSERIAL   PRIMARY KEY,
    alert_id    UUID        NOT NULL REFERENCES alerts (alert_id),
    from_status TEXT,
    to_status   TEXT        NOT NULL,
    actor       TEXT        NOT NULL,
    note        TEXT,
    at          TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_alert_transitions_alert ON alert_transitions (alert_id, at);

-- Transactional outbox: written in the same transaction as the state change.
CREATE TABLE outbox (
    id           UUID        PRIMARY KEY,
    topic        TEXT        NOT NULL,
    message_key  TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    headers      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;

-- Per-transaction facts retained for velocity/aggregate lookups by the ContextEnricher.
CREATE TABLE transaction_history (
    transaction_id TEXT          PRIMARY KEY,
    customer_id    TEXT          NOT NULL,
    account_id     TEXT          NOT NULL,
    amount         NUMERIC(19,4) NOT NULL CHECK (amount >= 0),
    currency       CHAR(3)       NOT NULL,
    device_id      TEXT,
    country        CHAR(2),
    occurred_at    TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_txn_history_account_time ON transaction_history (account_id, occurred_at);
