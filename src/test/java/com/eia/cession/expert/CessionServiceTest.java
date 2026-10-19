package com.eia.cession.expert;

import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CessionServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID POLICY = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID CONTRACT = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    @Mock
    private CessionRepository repository;

    @Mock
    private OutboxRepository outbox;

    @InjectMocks
    private CessionService service;

    private static CreateCessionRequest request(String book, String premium, String loss, String currency) {
        return new CreateCessionRequest(TENANT, book, POLICY, CONTRACT,
                new BigDecimal(premium), new BigDecimal(loss), currency);
    }

    private static Cession cession(CessionStatus status, int version) {
        return new Cession(ID, TENANT, "DIRECT", "CES-000000001", POLICY, CONTRACT, status,
                new BigDecimal("1000.0000"), new BigDecimal("250.0000"), "USD", version, NOW, NOW,
                null, null, null, null);
    }

    private static Cession handedOff() {
        return new Cession(ID, TENANT, "DIRECT", "CES-000000001", POLICY, CONTRACT,
                CessionStatus.HANDED_OFF, new BigDecimal("1000.0000"), new BigDecimal("250.0000"),
                "USD", 1, NOW, NOW, "FSRI-CES-DIRECT-000001", "POSTED", NOW, null);
    }

    private static Cession failed() {
        return new Cession(ID, TENANT, "DIRECT", "CES-000000001", POLICY, CONTRACT,
                CessionStatus.RULE_APPLIED, new BigDecimal("1000.0000"), new BigDecimal("250.0000"),
                "USD", 0, NOW, NOW, null, "FAILED", null, "FSRI_UNAVAILABLE: timeout");
    }

    @Test
    void rejectsNegativeCededLossEvenWhenBeanValidationIsBypassed() {
        assertThatThrownBy(() -> service.create(request("DIRECT", "10.0000", "-0.0001", "USD")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("VALIDATION");
                    assertThat(ex.getDetails()).containsEntry("cededLoss", "must be zero or greater");
                });

        verify(repository, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsNullCededPremium() {
        assertThatThrownBy(() -> service.create(
                new CreateCessionRequest(TENANT, "DIRECT", POLICY, CONTRACT, null, BigDecimal.ZERO, "USD")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getDetails()).containsEntry("cededPremium", "must not be null"));
    }

    @Test
    void rejectsAssumedBook() {
        assertThatThrownBy(() -> service.create(request("ASSUMED", "10.0000", "0.0000", "USD")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("DIRECT-only");
    }

    @Test
    void rejectsInvalidCurrency() {
        assertThatThrownBy(() -> service.create(request("DIRECT", "10.0000", "0.0000", "US")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getDetails()).containsEntry("currency", "must be a three-letter currency code"));
    }

    @Test
    void normalizesCurrencyBeforePersisting() {
        when(repository.create(eq(TENANT), eq(POLICY), eq(CONTRACT), any(), any(), eq("USD")))
                .thenReturn(cession(CessionStatus.OPEN, 0));

        Cession created = service.create(request("DIRECT", "1000.0000", "250.0000", "usd"));

        assertThat(created.status()).isEqualTo(CessionStatus.OPEN);
        verify(repository).create(eq(TENANT), eq(POLICY), eq(CONTRACT),
                eq(new BigDecimal("1000.0000")), eq(new BigDecimal("250.0000")), eq("USD"));
    }

    @Test
    void missingCessionRaisesNotFound() {
        when(repository.findById(TENANT, ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(TENANT, ID))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void appliesLegalStatusTransition() {
        when(repository.findById(TENANT, ID)).thenReturn(Optional.of(cession(CessionStatus.OPEN, 0)));
        when(repository.updateStatus(TENANT, ID, CessionStatus.RULE_APPLIED, 0))
                .thenReturn(Optional.of(cession(CessionStatus.RULE_APPLIED, 1)));

        Cession updated = service.transition(TENANT, ID, CessionStatus.RULE_APPLIED, 0);

        assertThat(updated.status()).isEqualTo(CessionStatus.RULE_APPLIED);
        assertThat(updated.version()).isEqualTo(1);
    }

    @Test
    void rejectsSkippedLifecycleStep() {
        when(repository.findById(TENANT, ID)).thenReturn(Optional.of(cession(CessionStatus.OPEN, 0)));

        assertThatThrownBy(() -> service.transition(TENANT, ID, CessionStatus.HANDED_OFF, 0))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVALID_STATUS_TRANSITION");
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                });

        verify(repository, never()).updateStatus(any(), any(), any(), anyInt());
    }

    @Test
    void rejectsStaleVersion() {
        when(repository.findById(TENANT, ID)).thenReturn(Optional.of(cession(CessionStatus.OPEN, 3)));

        assertThatThrownBy(() -> service.transition(TENANT, ID, CessionStatus.RULE_APPLIED, 2))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("CONFLICT");
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                });
    }

    @Test
    void refusesHandoffStateThroughThePlainStatusPath() {
        when(repository.findById(TENANT, ID)).thenReturn(Optional.of(cession(CessionStatus.RULE_APPLIED, 0)));

        assertThatThrownBy(() -> service.transition(TENANT, ID, CessionStatus.HANDOFF_PENDING, 0))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("HANDOFF_REQUIRED");
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                });

        verify(repository, never()).updateStatus(any(), any(), any(), anyInt());
    }

    @Test
    void recordsFsriAcknowledgementAndMovesToHandedOff() {
        when(repository.findById(TENANT, ID)).thenReturn(Optional.of(cession(CessionStatus.RULE_APPLIED, 0)));
        when(repository.recordHandoff(TENANT, ID, 0, "FSRI-CES-DIRECT-000001", "POSTED", NOW))
                .thenReturn(Optional.of(handedOff()));

        Cession updated = service.recordHandoff(TENANT, ID,
                new RecordHandoffRequest(0, "FSRI-CES-DIRECT-000001", "POSTED", NOW));

        assertThat(updated.status()).isEqualTo(CessionStatus.HANDED_OFF);
        assertThat(updated.fsriHandoffId()).isEqualTo("FSRI-CES-DIRECT-000001");
        assertThat(updated.fsriStatus()).isEqualTo("POSTED");
        assertThat(updated.fsriHandedOffAt()).isEqualTo(NOW);
        verify(repository).recordHandoff(TENANT, ID, 0, "FSRI-CES-DIRECT-000001", "POSTED", NOW);
    }

    @Test
    void rejectsHandoffFromANonHandoffReadyStatus() {
        when(repository.findById(TENANT, ID)).thenReturn(Optional.of(cession(CessionStatus.OPEN, 0)));

        assertThatThrownBy(() -> service.recordHandoff(TENANT, ID,
                new RecordHandoffRequest(0, "FSRI-CES-DIRECT-000001", "POSTED", NOW)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVALID_STATUS_TRANSITION");
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                });

        verify(repository, never()).recordHandoff(any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void rejectsStaleHandoffVersionWithoutChangingState() {
        when(repository.findById(TENANT, ID)).thenReturn(Optional.of(cession(CessionStatus.RULE_APPLIED, 2)));

        assertThatThrownBy(() -> service.recordHandoff(TENANT, ID,
                new RecordHandoffRequest(1, "FSRI-CES-DIRECT-000001", "POSTED", NOW)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("CONFLICT");
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                });

        verify(repository, never()).recordHandoff(any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void recordsHandoffFailureWithoutChangingLifecycleStatus() {
        when(repository.findById(TENANT, ID)).thenReturn(Optional.of(cession(CessionStatus.RULE_APPLIED, 0)));
        when(repository.recordHandoffFailure(TENANT, ID, 0, "FSRI_UNAVAILABLE: timeout"))
                .thenReturn(Optional.of(failed()));

        Cession updated = service.recordHandoffFailure(TENANT, ID,
                new RecordHandoffFailureRequest(0, "FSRI_UNAVAILABLE: timeout"));

        assertThat(updated.status()).isEqualTo(CessionStatus.RULE_APPLIED);
        assertThat(updated.version()).isZero();
        assertThat(updated.fsriStatus()).isEqualTo("FAILED");
        assertThat(updated.fsriLastError()).isEqualTo("FSRI_UNAVAILABLE: timeout");
        assertThat(updated.fsriHandoffId()).isNull();
    }

    @Test
    void statusLifecycleIsTerminalAfterConfirmedAndCancelled() {
        assertThat(CessionStatus.CONFIRMED.isTerminal()).isTrue();
        assertThat(CessionStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(CessionStatus.OPEN.isTerminal()).isFalse();
        assertThat(CessionStatus.HANDOFF_PENDING.canTransitionTo(CessionStatus.REJECTED)).isTrue();
        assertThat(CessionStatus.REJECTED.canTransitionTo(CessionStatus.HANDOFF_PENDING)).isTrue();
        assertThat(CessionStatus.CONFIRMED.canTransitionTo(CessionStatus.OPEN)).isFalse();
    }
}
