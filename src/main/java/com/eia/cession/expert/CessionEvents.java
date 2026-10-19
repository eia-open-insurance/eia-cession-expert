package com.eia.cession.expert;

import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventActor;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.events.EventIds;
import com.eia.platform.libs.events.EventMetadata;
import com.eia.platform.libs.events.EventSubject;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Event factory for the Cession vertical. Event names are the Cession slice
 * names; they differ from the planned catalog names (see the rollout report).
 *
 * <p>Cession is a DIRECT-only vertical, so every event carries
 * {@code book=DIRECT}. Rows start at version 0 for API compatibility, while the
 * canonical envelope requires {@code aggregateVersion >= 1}; the event version
 * is therefore the row version plus one (create -> 1, first update -> 2).</p>
 */
final class CessionEvents {
    static final String CREATED = "cession.cession.created";
    static final String UPDATED = "cession.cession.updated";
    static final String HANDED_OFF = "cession.cession.handed-off";

    private static final Book EVENT_BOOK = Book.DIRECT;
    private static final String VERTICAL = "cession";
    private static final String AGGREGATE_TYPE = "Cession";
    private static final String SOURCE_BUILD = "eia-cession-expert@0.1.0-SNAPSHOT";

    private CessionEvents() {
    }

    static EventEnvelope created(Cession cession, Instant now) {
        Map<String, Object> data = identity(cession);
        data.put("status", cession.status().name());
        data.put("cededPremium", money(cession.cededPremium()));
        data.put("cededLoss", money(cession.cededLoss()));
        data.put("currency", cession.currency());
        data.put("version", cession.version());

        return envelope(cession, CREATED, cession.version() + 1L, now, data);
    }

    static EventEnvelope updated(Cession before, Cession after, Instant now) {
        Map<String, Object> data = identity(after);
        data.put("changedFields", List.of("status"));
        data.put("version", after.version());
        data.put("from", before.status().name());
        data.put("to", after.status().name());

        return envelope(after, UPDATED, after.version() + 1L, now, data);
    }

    static EventEnvelope handedOff(Cession before, Cession after, Instant now) {
        Map<String, Object> data = identity(after);
        data.put("version", after.version());
        data.put("from", before.status().name());
        data.put("to", after.status().name());
        data.put("status", after.status().name());
        data.put("fsriHandoffId", after.fsriHandoffId());
        data.put("fsriStatus", after.fsriStatus());
        if (after.fsriHandedOffAt() != null) {
            data.put("fsriHandedOffAt", after.fsriHandedOffAt().toString());
        }

        return envelope(after, HANDED_OFF, after.version() + 1L, now, data);
    }

    private static Map<String, Object> identity(Cession cession) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cessionId", cession.id());
        data.put("cessionNumber", cession.cessionNumber());
        data.put("businessBook", cession.businessBook());
        data.put("policyId", cession.policyId());
        data.put("cessionContractId", cession.cessionContractId());
        return data;
    }

    private static String money(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }

    private static EventEnvelope envelope(
            Cession cession, String eventType, long aggregateVersion, Instant now, Map<String, Object> data) {
        UUID eventId = EventIds.deterministic(
                cession.tenantId(), AGGREGATE_TYPE, cession.id(), aggregateVersion, eventType);
        return EventEnvelope.builder()
                .eventId(eventId)
                .eventType(eventType)
                .eventVersion(1)
                .occurredAt(now)
                .recordedAt(now)
                .tenantId(cession.tenantId())
                .book(EVENT_BOOK)
                .vertical(VERTICAL)
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(cession.id())
                .aggregateVersion(aggregateVersion)
                .sequence(0)
                .actor(EventActor.system("eia-cession-expert"))
                .subject(EventSubject.of(VERTICAL, "cession", cession.id(), cession.cessionNumber()))
                .links(List.of())
                .data(data)
                .metadata(EventMetadata.v1(SOURCE_BUILD))
                .build();
    }
}
