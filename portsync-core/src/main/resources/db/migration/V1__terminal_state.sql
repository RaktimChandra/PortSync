-- Terminal state: berths, quay cranes, vessel visits, containers, yard slots, truck visits.
-- All business timestamps (arrived_at, faulted_since, gate_in_at ...) are in SIMULATED time.
-- updated_at / processed_at style columns are wall-clock time.

CREATE TABLE berths (
    id          VARCHAR(8)  PRIMARY KEY,
    vessel_id   VARCHAR(32),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE cranes (
    id                 VARCHAR(8)  PRIMARY KEY,
    berth_id           VARCHAR(8)  REFERENCES berths (id),
    assigned_at        TIMESTAMPTZ,
    faulted            BOOLEAN     NOT NULL DEFAULT FALSE,
    fault_code         VARCHAR(64),
    faulted_since      TIMESTAMPTZ,
    last_heartbeat_at  TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_cranes_fault_time CHECK (faulted = (faulted_since IS NOT NULL))
);

CREATE TABLE vessels (
    id                 VARCHAR(32)  PRIMARY KEY,
    name               VARCHAR(128) NOT NULL,
    voyage             VARCHAR(32)  NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    berth_id           VARCHAR(8)   REFERENCES berths (id),
    arrived_at         TIMESTAMPTZ  NOT NULL,
    berthed_at         TIMESTAMPTZ,
    departed_at        TIMESTAMPTZ,
    discharge_planned  INT          NOT NULL CHECK (discharge_planned >= 0),
    load_planned       INT          NOT NULL CHECK (load_planned >= 0),
    discharged         INT          NOT NULL DEFAULT 0,
    loaded             INT          NOT NULL DEFAULT 0,
    CONSTRAINT ck_vessels_status CHECK (status IN ('WAITING', 'AT_BERTH', 'DEPARTED'))
);
CREATE INDEX ix_vessels_status ON vessels (status);

-- A berth can host at most one vessel at a time.
CREATE UNIQUE INDEX ux_vessels_one_per_berth ON vessels (berth_id) WHERE status = 'AT_BERTH';

CREATE TABLE containers (
    number         VARCHAR(11) PRIMARY KEY,
    status         VARCHAR(16) NOT NULL,
    vessel_id      VARCHAR(32),
    last_event_at  TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_containers_status CHECK (status IN ('ON_QUAY', 'DELIVERED', 'IN_YARD', 'LOADED', 'GATED_OUT'))
);

-- One row per physical slot (960 rows, seeded in V4).
-- The primary key enforces "one container per slot"; the partial unique index enforces
-- "a container is in at most one slot". The no-floating rule is enforced by the application
-- under a row lock on the stack and audited by the invariant checker.
CREATE TABLE yard_slots (
    block             CHAR(1)     NOT NULL,
    row_no            SMALLINT    NOT NULL,
    bay_no            SMALLINT    NOT NULL,
    tier              SMALLINT    NOT NULL,
    container_number  VARCHAR(11) REFERENCES containers (number),
    placed_at         TIMESTAMPTZ,
    PRIMARY KEY (block, row_no, bay_no, tier)
);
CREATE UNIQUE INDEX ux_yard_slots_container ON yard_slots (container_number) WHERE container_number IS NOT NULL;

CREATE TABLE truck_visits (
    id                VARCHAR(40) PRIMARY KEY,
    container_number  VARCHAR(11) NOT NULL,
    direction         VARCHAR(10) NOT NULL,
    lane              VARCHAR(4)  NOT NULL,
    truck_plate       VARCHAR(32),
    status            VARCHAR(8)  NOT NULL,
    gate_in_at        TIMESTAMPTZ NOT NULL,
    gate_out_at       TIMESTAMPTZ,
    CONSTRAINT ck_truck_visits_direction CHECK (direction IN ('DROP_OFF', 'PICK_UP')),
    CONSTRAINT ck_truck_visits_status CHECK (status IN ('OPEN', 'CLOSED'))
);
CREATE INDEX ix_truck_visits_open ON truck_visits (gate_in_at) WHERE status = 'OPEN';
CREATE INDEX ix_truck_visits_closed ON truck_visits (gate_out_at) WHERE status = 'CLOSED';
-- Only one open pick-up per container: two trucks cannot collect the same box.
CREATE UNIQUE INDEX ux_truck_visits_open_pickup ON truck_visits (container_number)
    WHERE status = 'OPEN' AND direction = 'PICK_UP';

-- Every quay crane move; the rolling productivity window is computed from this table.
CREATE TABLE crane_moves (
    id                BIGSERIAL   PRIMARY KEY,
    event_id          UUID        NOT NULL,
    crane_id          VARCHAR(8)  NOT NULL,
    vessel_id         VARCHAR(32) NOT NULL,
    container_number  VARCHAR(11) NOT NULL,
    kind              VARCHAR(10) NOT NULL,
    occurred_at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_crane_moves_crane_time ON crane_moves (crane_id, occurred_at);
CREATE INDEX ix_crane_moves_time ON crane_moves (occurred_at);

-- Human readable audit trail shown by the container lookup page.
CREATE TABLE container_history (
    id                BIGSERIAL   PRIMARY KEY,
    container_number  VARCHAR(11) NOT NULL,
    event_id          UUID,
    event_type        VARCHAR(20) NOT NULL,
    occurred_at       TIMESTAMPTZ NOT NULL,
    description       TEXT        NOT NULL
);
CREATE INDEX ix_container_history_number ON container_history (container_number, occurred_at, id);
