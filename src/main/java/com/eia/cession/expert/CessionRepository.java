package com.eia.cession.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CessionRepository {
    private final JdbcTemplate jdbc;

    public CessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Cession> MAPPER = (rs, rowNum) -> new Cession(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("business_book"),
            rs.getString("cession_number"),
            rs.getObject("policy_id", UUID.class),
            rs.getObject("cession_contract_id", UUID.class),
            CessionStatus.valueOf(rs.getString("status")),
            rs.getBigDecimal("ceded_premium"),
            rs.getBigDecimal("ceded_loss"),
            rs.getString("currency"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private static final String COLUMNS = """
            id, tenant_id, business_book, cession_number, policy_id, cession_contract_id, status,
            ceded_premium, ceded_loss, currency, version, created_at, updated_at
            """;

    public List<Cession> findAll(UUID tenantId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cession WHERE tenant_id = ? ORDER BY created_at DESC",
                MAPPER, tenantId);
    }

    public Optional<Cession> findById(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cession WHERE tenant_id = ? AND id = ?",
                MAPPER, tenantId, id).stream().findFirst();
    }

    public Cession create(UUID tenantId, UUID policyId, UUID cessionContractId,
                          BigDecimal cededPremium, BigDecimal cededLoss, String currency) {
        UUID id = UUID.randomUUID();
        String cessionNumber = "CES-" + String.format("%09d",
                jdbc.queryForObject("SELECT nextval('cession_number_seq')", Long.class));
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO cession (id, tenant_id, business_book, cession_number, policy_id, cession_contract_id,
                                     status, ceded_premium, ceded_loss, currency, version, created_at, updated_at)
                VALUES (?, ?, 'DIRECT', ?, ?, ?, 'OPEN', ?, ?, ?, 0, ?, ?)
                """,
                id, tenantId, cessionNumber, policyId, cessionContractId,
                cededPremium, cededLoss, currency,
                Timestamp.from(now), Timestamp.from(now));
        return findById(tenantId, id).orElseThrow();
    }

    public Optional<Cession> updateStatus(UUID tenantId, UUID id, CessionStatus status, int expectedVersion) {
        int updated = jdbc.update("""
                UPDATE cession
                   SET status = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND version = ?
                """, status.name(), Timestamp.from(Instant.now()), tenantId, id, expectedVersion);
        if (updated == 0) {
            return Optional.empty();
        }
        return findById(tenantId, id);
    }
}
