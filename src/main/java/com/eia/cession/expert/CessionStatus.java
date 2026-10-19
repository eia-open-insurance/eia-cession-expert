package com.eia.cession.expert;

import java.util.Map;
import java.util.Set;

/**
 * Cession case lifecycle from SPEC 11.9.3 (cession_case.status).
 *
 * <pre>
 * OPEN -> RULE_APPLIED -> HANDOFF_PENDING -> HANDED_OFF -> CONFIRMED
 * HANDOFF_PENDING / HANDED_OFF -> REJECTED -> HANDOFF_PENDING
 * OPEN / RULE_APPLIED / HANDOFF_PENDING / REJECTED -> CANCELLED
 * </pre>
 */
public enum CessionStatus {
    OPEN,
    RULE_APPLIED,
    HANDOFF_PENDING,
    HANDED_OFF,
    CONFIRMED,
    REJECTED,
    CANCELLED;

    private static final Map<CessionStatus, Set<CessionStatus>> ALLOWED = Map.of(
            OPEN, Set.of(RULE_APPLIED, CANCELLED),
            RULE_APPLIED, Set.of(HANDOFF_PENDING, CANCELLED),
            HANDOFF_PENDING, Set.of(HANDED_OFF, REJECTED, CANCELLED),
            HANDED_OFF, Set.of(CONFIRMED, REJECTED),
            REJECTED, Set.of(HANDOFF_PENDING, CANCELLED),
            CONFIRMED, Set.of(),
            CANCELLED, Set.of()
    );

    public boolean canTransitionTo(CessionStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isTerminal() {
        return ALLOWED.getOrDefault(this, Set.of()).isEmpty();
    }
}
