package com.eia.cession.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Cession(
        UUID id,
        UUID tenantId,
        String businessBook,
        String cessionNumber,
        UUID policyId,
        UUID cessionContractId,
        CessionStatus status,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cededPremium,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cededLoss,
        String currency,
        int version,
        Instant createdAt,
        Instant updatedAt,
        String fsriHandoffId,
        String fsriStatus,
        Instant fsriHandedOffAt,
        String fsriLastError
) {
    public Cession(UUID id,
                   UUID tenantId,
                   String businessBook,
                   String cessionNumber,
                   UUID policyId,
                   UUID cessionContractId,
                   CessionStatus status,
                   BigDecimal cededPremium,
                   BigDecimal cededLoss,
                   String currency,
                   int version,
                   Instant createdAt,
                   Instant updatedAt) {
        this(id, tenantId, businessBook, cessionNumber, policyId, cessionContractId, status,
                cededPremium, cededLoss, currency, version, createdAt, updatedAt,
                null, null, null, null);
    }
}
