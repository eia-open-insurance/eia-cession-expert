package com.eia.cession.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistence and transaction behaviour against a real schema in H2's
 * PostgreSQL compatibility mode. The critical property is that the lifecycle
 * status and the FS-RI acknowledgement move together, and that a stale version
 * cannot apply either one.
 */
@SpringBootTest
@ActiveProfiles("test")
class CessionHandoffPersistenceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID POLICY = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID CONTRACT = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    @Autowired
    private CessionService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM cession");
    }

    @Test
    void handoffWritesLifecycleAndFsriAcknowledgementTogether() {
        insert(CessionStatus.RULE_APPLIED, 3);

        Cession updated = service.recordHandoff(TENANT, ID,
                new RecordHandoffRequest(3, "FSRI-CES-DIRECT-000001", "POSTED", NOW));

        assertThat(updated.status()).isEqualTo(CessionStatus.HANDED_OFF);
        assertThat(updated.version()).isEqualTo(4);
        assertThat(updated.fsriHandoffId()).isEqualTo("FSRI-CES-DIRECT-000001");
        assertThat(updated.fsriStatus()).isEqualTo("POSTED");
        assertThat(updated.fsriHandedOffAt()).isEqualTo(NOW);
        assertThat(updated.fsriLastError()).isNull();

        assertThat(statusInDatabase()).isEqualTo("HANDED_OFF");
        assertThat(versionInDatabase()).isEqualTo(4);
        assertThat(column("fsri_handoff_id")).isEqualTo("FSRI-CES-DIRECT-000001");
        assertThat(column("fsri_status")).isEqualTo("POSTED");
    }

    @Test
    void staleHandoffVersionCannotApplyAnyPartOfTheChange() {
        insert(CessionStatus.RULE_APPLIED, 3);

        assertThatThrownBy(() -> service.recordHandoff(TENANT, ID,
                new RecordHandoffRequest(2, "FSRI-CES-DIRECT-000001", "POSTED", NOW)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("CONFLICT");
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                });

        assertThat(statusInDatabase()).isEqualTo("RULE_APPLIED");
        assertThat(versionInDatabase()).isEqualTo(3);
        assertThat(column("fsri_handoff_id")).isNull();
        assertThat(column("fsri_status")).isNull();
    }

    @Test
    void failedHandoffStaysRetryableAndRetryCanSucceed() {
        insert(CessionStatus.RULE_APPLIED, 3);

        Cession failed = service.recordHandoffFailure(TENANT, ID,
                new RecordHandoffFailureRequest(3, "FSRI_UNAVAILABLE: read timeout"));

        assertThat(failed.status()).isEqualTo(CessionStatus.RULE_APPLIED);
        assertThat(failed.version()).isEqualTo(3);
        assertThat(failed.fsriStatus()).isEqualTo("FAILED");
        assertThat(failed.fsriLastError()).isEqualTo("FSRI_UNAVAILABLE: read timeout");
        assertThat(failed.fsriHandoffId()).isNull();
        assertThat(failed.fsriHandedOffAt()).isNull();

        Cession retried = service.recordHandoff(TENANT, ID,
                new RecordHandoffRequest(3, "FSRI-CES-DIRECT-000001", "POSTED", NOW));

        assertThat(retried.status()).isEqualTo(CessionStatus.HANDED_OFF);
        assertThat(retried.version()).isEqualTo(4);
        assertThat(retried.fsriHandoffId()).isEqualTo("FSRI-CES-DIRECT-000001");
        assertThat(retried.fsriLastError()).isNull();
    }

    @Test
    void nonHandoffReadyCaseIsRejectedWithoutWriting() {
        insert(CessionStatus.OPEN, 0);

        assertThatThrownBy(() -> service.recordHandoff(TENANT, ID,
                new RecordHandoffRequest(0, "FSRI-CES-DIRECT-000001", "POSTED", NOW)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVALID_STATUS_TRANSITION");
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                });

        assertThat(statusInDatabase()).isEqualTo("OPEN");
        assertThat(versionInDatabase()).isZero();
        assertThat(column("fsri_handoff_id")).isNull();
    }

    @Test
    void plainStatusPathCannotWriteHandoffState() {
        insert(CessionStatus.RULE_APPLIED, 0);

        assertThatThrownBy(() -> service.transition(TENANT, ID, CessionStatus.HANDOFF_PENDING, 0))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("HANDOFF_REQUIRED"));

        assertThat(statusInDatabase()).isEqualTo("RULE_APPLIED");
        assertThat(versionInDatabase()).isZero();
    }

    private void insert(CessionStatus status, int version) {
        jdbc.update("""
                INSERT INTO cession (id, tenant_id, business_book, cession_number, policy_id,
                                     cession_contract_id, status, ceded_premium, ceded_loss, currency,
                                     version, created_at, updated_at)
                VALUES (?, ?, 'DIRECT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                ID, TENANT, "CES-000000001", POLICY, CONTRACT, status.name(),
                new BigDecimal("1000.0000"), new BigDecimal("250.0000"), "USD", version,
                Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private String statusInDatabase() {
        return jdbc.queryForObject("SELECT status FROM cession WHERE id = ?", String.class, ID);
    }

    private int versionInDatabase() {
        Integer version = jdbc.queryForObject("SELECT version FROM cession WHERE id = ?", Integer.class, ID);
        return version == null ? -1 : version;
    }

    private String column(String name) {
        return jdbc.queryForObject("SELECT " + name + " FROM cession WHERE id = ?", String.class, ID);
    }
}
