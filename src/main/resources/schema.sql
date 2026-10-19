CREATE SEQUENCE IF NOT EXISTS cession_number_seq START 1;

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
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_cession_number UNIQUE (tenant_id, cession_number),
    CONSTRAINT ck_cession_book CHECK (business_book = 'DIRECT'),
    CONSTRAINT ck_cession_amounts CHECK (ceded_premium >= 0 AND ceded_loss >= 0),
    CONSTRAINT ck_cession_status CHECK (status IN (
        'OPEN', 'RULE_APPLIED', 'HANDOFF_PENDING', 'HANDED_OFF', 'CONFIRMED', 'REJECTED', 'CANCELLED'
    ))
);

CREATE INDEX IF NOT EXISTS ix_cession_tenant_policy ON cession (tenant_id, policy_id);
CREATE INDEX IF NOT EXISTS ix_cession_tenant_contract ON cession (tenant_id, cession_contract_id);
