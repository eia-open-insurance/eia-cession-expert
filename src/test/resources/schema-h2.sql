CREATE SEQUENCE IF NOT EXISTS cession_number_seq START WITH 1;

CREATE TABLE IF NOT EXISTS cession (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    business_book VARCHAR(16) NOT NULL DEFAULT 'DIRECT',
    cession_number VARCHAR(32) NOT NULL,
    policy_id UUID NOT NULL,
    cession_contract_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    ceded_premium NUMERIC(19, 4) NOT NULL,
    ceded_loss NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    fsri_handoff_id VARCHAR(64),
    fsri_status VARCHAR(32),
    fsri_handed_off_at TIMESTAMP WITH TIME ZONE,
    fsri_last_error VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_cession_number UNIQUE (tenant_id, cession_number),
    CONSTRAINT ck_cession_book CHECK (business_book = 'DIRECT'),
    CONSTRAINT ck_cession_amounts CHECK (ceded_premium >= 0 AND ceded_loss >= 0),
    CONSTRAINT ck_cession_status CHECK (status IN (
        'OPEN', 'RULE_APPLIED', 'HANDOFF_PENDING', 'HANDED_OFF', 'CONFIRMED', 'REJECTED', 'CANCELLED'
    ))
);

-- H2-compatible copy of the PostgreSQL outbox table. JSON payloads are TEXT
-- here; production DDL uses jsonb.
CREATE TABLE IF NOT EXISTS outbox_event (
    id UUID PRIMARY KEY,
    event_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    event_version INTEGER NOT NULL,
    payload TEXT NOT NULL,
    headers TEXT NOT NULL,
    partition_key VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    sent_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_outbox_event_status
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD')),
    CONSTRAINT uq_outbox_event_aggregate_version
        UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
);

CREATE INDEX IF NOT EXISTS ix_outbox_event_due
    ON outbox_event (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS ix_outbox_event_partition
    ON outbox_event (partition_key, event_sequence);
