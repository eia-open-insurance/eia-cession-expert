package com.eia.cession.expert;

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
        BigDecimal cededPremium,
        BigDecimal cededLoss,
        String currency,
        int version,
        Instant createdAt,
        Instant updatedAt
) {}
