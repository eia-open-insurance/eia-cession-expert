package com.eia.cession.expert;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Expert-side invariant enforcement. The domain owns business decisions, but the
 * expert never persists a record that breaks the direct-book or amount invariants.
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

    public Cession transition(UUID tenantId, UUID id, CessionStatus target, int expectedVersion) {
        Cession current = get(tenantId, id);
        if (current.version() != expectedVersion) {
            throw ApiException.conflict("Cession " + id + " was modified; expected version "
                    + expectedVersion + " but found " + current.version());
        }
        if (!current.status().canTransitionTo(target)) {
            throw ApiException.badRequest("INVALID_STATUS_TRANSITION",
                    "Cession " + id + " cannot move from " + current.status() + " to " + target);
        }
        return repository.updateStatus(tenantId, id, target, expectedVersion)
                .orElseThrow(() -> ApiException.conflict("Cession " + id + " was modified concurrently"));
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
