package com.eia.cession.expert;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Expert-side invariant enforcement. The domain owns business decisions, but the
 * expert never persists a record that breaks the direct-book or amount invariants.
 *
 * <p>Hand-off state is persisted only through the dedicated methods, which
 * combine the lifecycle guard, the optimistic version check, and the FS-RI
 * acknowledgement in one transaction. A plain status update can never write
 * {@code HANDOFF_PENDING} or {@code HANDED_OFF}.</p>
 */
@Service
public class CessionService {
    private final CessionRepository repository;

    public CessionService(CessionRepository repository) {
        this.repository = repository;
    }

    public List<Cession> list(UUID tenantId) {
        return repository.findAll(tenantId);
    }

    public Cession get(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id)
                .orElseThrow(() -> ApiException.notFound("Cession not found: " + id));
    }

    public Cession create(CreateCessionRequest request) {
        requireDirectBook(request.businessBook());
        requireNonNegative("cededPremium", request.cededPremium());
        requireNonNegative("cededLoss", request.cededLoss());
        String currency = requireCurrency(request.currency());
        return repository.create(request.tenantId(), request.policyId(), request.cessionContractId(),
                request.cededPremium(), request.cededLoss(), currency);
    }

    @Transactional
    public Cession transition(UUID tenantId, UUID id, CessionStatus target, int expectedVersion) {
        Cession current = get(tenantId, id);
        requireVersion(current, expectedVersion);
        if (!current.status().canTransitionTo(target)) {
            throw ApiException.badRequest("INVALID_STATUS_TRANSITION",
                    "Cession " + id + " cannot move from " + current.status() + " to " + target);
        }
        if (target == CessionStatus.HANDOFF_PENDING || target == CessionStatus.HANDED_OFF) {
            throw ApiException.badRequest("HANDOFF_REQUIRED",
                    "Cession " + id + " can move to " + target + " only through the FS-RI handoff endpoint");
        }
        return repository.updateStatus(tenantId, id, target, expectedVersion)
                .orElseThrow(() -> ApiException.conflict("Cession " + id + " was modified concurrently"));
    }

    /**
     * Persist the FS-RI acknowledgement and move to HANDED_OFF. The repository
     * performs both in a single optimistic UPDATE so a concurrent writer cannot
     * leave a handed-off case without its FS-RI reference.
     */
    @Transactional
    public Cession recordHandoff(UUID tenantId, UUID id, RecordHandoffRequest request) {
        Cession current = get(tenantId, id);
        int expectedVersion = requireVersion(request.expectedVersion());
        requireVersion(current, expectedVersion);
        requireHandoffReady(current);
        String fsriHandoffId = requireText("fsriHandoffId", request.fsriHandoffId(), 64);
        String fsriStatus = requireText("fsriStatus", request.fsriStatus(), 32);
        if (request.fsriHandedOffAt() == null) {
            throw ApiException.validation("fsriHandedOffAt", "must not be null");
        }
        Instant handedOffAt = request.fsriHandedOffAt();
        return repository.recordHandoff(tenantId, id, expectedVersion,
                        fsriHandoffId, fsriStatus, handedOffAt)
                .orElseThrow(() -> ApiException.conflict("Cession " + id + " was modified concurrently"));
    }

    /**
     * Record a failed FS-RI attempt without changing the lifecycle status. The
     * case remains retryable and its version is unchanged, so the retry reuses
     * the same transition version and idempotency key.
     */
    @Transactional
    public Cession recordHandoffFailure(UUID tenantId, UUID id, RecordHandoffFailureRequest request) {
        Cession current = get(tenantId, id);
        int expectedVersion = requireVersion(request.expectedVersion());
        requireVersion(current, expectedVersion);
        requireHandoffReady(current);
        String lastError = requireText("fsriLastError", request.fsriLastError(), 1000);
        return repository.recordHandoffFailure(tenantId, id, expectedVersion, lastError)
                .orElseThrow(() -> ApiException.conflict("Cession " + id + " was modified concurrently"));
    }

    private static void requireVersion(Cession current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw ApiException.conflict("Cession " + current.id() + " was modified; expected version "
                    + expectedVersion + " but found " + current.version());
        }
    }

    private static int requireVersion(Integer expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0) {
            throw ApiException.validation("expectedVersion", "must be zero or greater");
        }
        return expectedVersion;
    }

    private static void requireHandoffReady(Cession current) {
        if (current.status() != CessionStatus.RULE_APPLIED
                && current.status() != CessionStatus.REJECTED) {
            throw ApiException.badRequest("INVALID_STATUS_TRANSITION",
                    "Cession " + current.id() + " cannot be handed off from " + current.status());
        }
    }

    private static String requireText(String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw ApiException.validation(field, "must not be blank");
        }
        if (value.length() > maxLength) {
            throw ApiException.validation(field, "must be at most " + maxLength + " characters");
        }
        return value;
    }

    private static void requireDirectBook(String businessBook) {
        if (businessBook == null || !"DIRECT".equals(businessBook)) {
            throw ApiException.badRequest("BOOK_SCOPE_VIOLATION",
                    "Cession is a DIRECT-only vertical; businessBook must be DIRECT");
        }
    }

    private static void requireNonNegative(String field, BigDecimal amount) {
        if (amount == null) {
            throw ApiException.validation(field, "must not be null");
        }
        if (amount.signum() < 0) {
            throw ApiException.validation(field, "must be zero or greater");
        }
    }

    private static String requireCurrency(String currency) {
        if (currency == null || !currency.matches("[A-Za-z]{3}")) {
            throw ApiException.validation("currency", "must be a three-letter currency code");
        }
        return currency.toUpperCase(Locale.ROOT);
    }
}
