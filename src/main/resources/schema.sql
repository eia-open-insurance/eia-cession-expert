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

-- Expand-only migration for databases created before FS-RI hand-off state.
ALTER TABLE cession ADD COLUMN IF NOT EXISTS fsri_handoff_id VARCHAR(64);
ALTER TABLE cession ADD COLUMN IF NOT EXISTS fsri_status VARCHAR(32);
ALTER TABLE cession ADD COLUMN IF NOT EXISTS fsri_handed_off_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE cession ADD COLUMN IF NOT EXISTS fsri_last_error VARCHAR(1000);

CREATE INDEX IF NOT EXISTS ix_cession_tenant_policy ON cession (tenant_id, policy_id);
CREATE INDEX IF NOT EXISTS ix_cession_tenant_contract ON cession (tenant_id, cession_contract_id);
CREATE INDEX IF NOT EXISTS ix_cession_fsri_handoff ON cession (tenant_id, fsri_handoff_id);

-- Transactional outbox (SPEC 7.5.1). The canonical copy lives in
-- eia-platform/libs/src/main/resources/db/outbox_event.sql; keep both in sync.
CREATE TABLE IF NOT EXISTS outbox_event (
    id UUID PRIMARY KEY,
    event_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    event_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    partition_key VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    CONSTRAINT ck_outbox_event_status
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD')),
    CONSTRAINT uq_outbox_event_aggregate_version
        UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
);

CREATE INDEX IF NOT EXISTS ix_outbox_event_due
    ON outbox_event (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS ix_outbox_event_partition
    ON outbox_event (partition_key, event_sequence);
