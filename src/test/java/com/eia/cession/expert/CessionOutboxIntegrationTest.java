package com.eia.cession.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class CessionOutboxIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID CONTRACT = UUID.fromString("44444444-4444-7444-8444-444444444444");

    @Autowired
    private CessionService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM cession");
    }

    @Test
    void createWritesTheCessionAndCreatedEventTogether() {
        Cession cession = service.create(request());

        Map<String, Object> event = jdbc.queryForMap("""
                SELECT event_type, aggregate_type, aggregate_version, status, tenant_id,
                       payload, headers, partition_key, occurred_at, recorded_at, next_attempt_at
                  FROM outbox_event
                 WHERE aggregate_id = ?
                """, cession.id());

        assertThat(event.get("event_type")).isEqualTo("cession.cession.created");
        assertThat(event.get("aggregate_type")).isEqualTo("Cession");
        assertThat(event.get("aggregate_version")).isEqualTo(1L);
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("tenant_id")).isEqualTo(TENANT);
        assertThat(event.get("partition_key")).isEqualTo(cession.id().toString());
        assertThat(event.get("occurred_at")).isNotNull();
        assertThat(event.get("recorded_at")).isNotNull();
        assertThat(event.get("next_attempt_at")).isNotNull();

        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"cessionId\":\"" + cession.id() + "\"")
                .contains("\"cessionNumber\":\"" + cession.cessionNumber() + "\"")
                .contains("\"businessBook\":\"DIRECT\"")
                .contains("\"policyId\":\"" + POLICY + "\"")
                .contains("\"cessionContractId\":\"" + CONTRACT + "\"")
                .contains("\"status\":\"OPEN\"")
                .contains("\"cededPremium\":\"1000.0000\"")
                .contains("\"cededLoss\":\"250.0000\"")
                .contains("\"currency\":\"USD\"");
        String headers = (String) event.get("headers");
        assertThat(headers)
                .contains("\"book\":\"DIRECT\"")
                .contains("\"vertical\":\"cession\"")
                .contains("\"businessKey\":\"" + cession.cessionNumber() + "\"")
                .contains("\"schemaVersion\":\"1.0.0\"");
    }

    @Test
    void createRollbackLeavesNeitherTheCessionNorTheEvent() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.create(request());
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("cession")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void statusTransitionWritesTheUpdatedEventInTheSameTransaction() {
        Cession cession = service.create(request());

        Cession updated = service.transition(TENANT, cession.id(), CessionStatus.RULE_APPLIED, 0);

        assertThat(updated.status()).isEqualTo(CessionStatus.RULE_APPLIED);
        assertThat(updated.version()).isEqualTo(1);

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT event_type, aggregate_version, payload
                  FROM outbox_event
                 WHERE aggregate_id = ?
                 ORDER BY event_sequence
                """, cession.id());

        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("event_type")).isEqualTo("cession.cession.created");
        assertThat(events.get(1).get("event_type")).isEqualTo("cession.cession.updated");
        assertThat(events.get(1).get("aggregate_version")).isEqualTo(2L);

        String payload = (String) events.get(1).get("payload");
        assertThat(payload)
                .contains("\"changedFields\":[\"status\"]")
                .contains("\"version\":1")
                .contains("\"from\":\"OPEN\"")
                .contains("\"to\":\"RULE_APPLIED\"")
                .contains("\"cessionNumber\":\"" + cession.cessionNumber() + "\"");
    }

    @Test
    void handoffWritesTheHandedOffEventInTheSameTransaction() {
        Cession cession = service.create(request());
        service.transition(TENANT, cession.id(), CessionStatus.RULE_APPLIED, 0);

        Cession handedOff = service.recordHandoff(TENANT, cession.id(),
                new RecordHandoffRequest(1, "FSRI-CES-DIRECT-000001", "POSTED",
                        java.time.Instant.parse("2026-10-19T12:00:00Z")));

        assertThat(handedOff.status()).isEqualTo(CessionStatus.HANDED_OFF);
        assertThat(handedOff.version()).isEqualTo(2);

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT event_type, aggregate_version, payload
                  FROM outbox_event
                 WHERE aggregate_id = ?
                 ORDER BY event_sequence
                """, cession.id());

        assertThat(events).hasSize(3);
        assertThat(events.get(2).get("event_type")).isEqualTo("cession.cession.handed-off");
        assertThat(events.get(2).get("aggregate_version")).isEqualTo(3L);
        String payload = (String) events.get(2).get("payload");
        assertThat(payload)
                .contains("\"status\":\"HANDED_OFF\"")
                .contains("\"from\":\"RULE_APPLIED\"")
                .contains("\"to\":\"HANDED_OFF\"")
                .contains("\"fsriHandoffId\":\"FSRI-CES-DIRECT-000001\"")
                .contains("\"fsriStatus\":\"POSTED\"");
    }

    @Test
    void transitionRollbackKeepsTheOldStateAndWritesNoEvent() {
        Cession cession = service.create(request());

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.transition(TENANT, cession.id(), CessionStatus.RULE_APPLIED, 0);
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM cession WHERE id = ?", String.class, cession.id()))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject(
                "SELECT version FROM cession WHERE id = ?", Integer.class, cession.id()))
                .isZero();
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = 'cession.cession.updated'
                """, Integer.class, cession.id())).isZero();
    }

    private CreateCessionRequest request() {
        return new CreateCessionRequest(TENANT, "DIRECT", POLICY, CONTRACT,
                new BigDecimal("1000.0000"), new BigDecimal("250.0000"), "USD");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
