-- Idempotency, ordering and dead-letter bookkeeping.

-- One row per eventId ever processed. Inserted in the SAME transaction as the state change,
-- so "state changed" and "event marked processed" commit or roll back together.
CREATE TABLE processed_events (
    event_id          UUID        PRIMARY KEY,
    event_type        VARCHAR(20) NOT NULL,
    entity_id         VARCHAR(64) NOT NULL,
    occurred_at       TIMESTAMPTZ NOT NULL,
    outcome           VARCHAR(16) NOT NULL,
    reason            VARCHAR(40),
    detail            TEXT,
    source_topic      VARCHAR(64),
    source_partition  INT,
    source_offset     BIGINT,
    processed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_processed_outcome CHECK (outcome IN ('APPLIED', 'IGNORED_STALE', 'REJECTED'))
);
CREATE INDEX ix_processed_events_time ON processed_events (processed_at DESC);

-- Latest applied event time per entity: the out-of-order rule compares against this.
CREATE TABLE entity_versions (
    entity_kind       VARCHAR(16) NOT NULL,
    entity_id         VARCHAR(64) NOT NULL,
    last_occurred_at  TIMESTAMPTZ NOT NULL,
    last_event_id     UUID        NOT NULL,
    PRIMARY KEY (entity_kind, entity_id)
);

-- Materialised view of the terminal.dlq topic for the API. The topic is the source of truth;
-- the unique key on the source coordinates makes re-consumption harmless.
CREATE TABLE dlq_events (
    id                BIGSERIAL   PRIMARY KEY,
    event_id          UUID,
    event_type        VARCHAR(20),
    entity_id         VARCHAR(64),
    reason            VARCHAR(40) NOT NULL,
    detail            TEXT,
    raw_value         TEXT,
    source_topic      VARCHAR(64) NOT NULL,
    source_partition  INT         NOT NULL,
    source_offset     BIGINT      NOT NULL,
    dlq_partition     INT         NOT NULL,
    dlq_offset        BIGINT      NOT NULL,
    dead_lettered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_dlq_source UNIQUE (source_topic, source_partition, source_offset)
);
CREATE INDEX ix_dlq_events_time ON dlq_events (dead_lettered_at DESC);
CREATE INDEX ix_dlq_events_entity ON dlq_events (entity_id);
