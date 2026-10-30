package dev.gautamgupta.gateway.persistence.ledger;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One metered request. There is no prompt or completion text (ADR-0008, NFR-004).
 * {@code estCostMicros} is null when the price table has no row for the model.
 */
public record UsageLedgerEntry(
        UUID id,
        OffsetDateTime ts,
        String tenantId,
        String requestId,
        String route,
        String provider,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Long estCostMicros,
        String cache,
        Integer latencyMs,
        int status,
        String errorCode
) {
}
