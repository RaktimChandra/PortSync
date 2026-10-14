-- SLA alerts. Lifecycle: OPEN -> ACKNOWLEDGED -> RESOLVED (or OPEN -> RESOLVED).
-- opened_at / last_seen_at / resolved_at are simulated time.
CREATE TABLE alerts (
    id               BIGSERIAL     PRIMARY KEY,
    rule             VARCHAR(40)   NOT NULL,
    entity_id        VARCHAR(64)   NOT NULL,
    severity         VARCHAR(10)   NOT NULL,
    state            VARCHAR(14)   NOT NULL,
    message          TEXT          NOT NULL,
    observed_value   NUMERIC(12,2),
    threshold        NUMERIC(12,2),
    opened_at        TIMESTAMPTZ   NOT NULL,
    last_seen_at     TIMESTAMPTZ   NOT NULL,
    acknowledged_at  TIMESTAMPTZ,
    resolved_at      TIMESTAMPTZ,
    resolution       VARCHAR(8),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_alerts_state CHECK (state IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    CONSTRAINT ck_alerts_severity CHECK (severity IN ('WARNING', 'CRITICAL')),
    CONSTRAINT ck_alerts_resolution CHECK (resolution IS NULL OR resolution IN ('AUTO', 'MANUAL'))
);

-- De-duplication: at most one active (non-resolved) alert per rule + entity.
CREATE UNIQUE INDEX ux_alerts_active ON alerts (rule, entity_id) WHERE state IN ('OPEN', 'ACKNOWLEDGED');
CREATE INDEX ix_alerts_state ON alerts (state, opened_at DESC);
