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
