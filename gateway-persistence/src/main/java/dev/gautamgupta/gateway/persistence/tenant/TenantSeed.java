package dev.gautamgupta.gateway.persistence.tenant;

import dev.gautamgupta.gateway.tenancy.apikey.ApiKeyRecord;
import java.time.Duration;

/** One tenant the process may insert at startup. Secrets are hashed before they are stored. */
public record TenantSeed(
        String tenantId,
        String displayName,
        ApiKeyRecord.TenantStatus status,
        String keyId,
        String secret,
        int rateCapacity,
        double rateRefillPerSecond,
        Long tokensPerMinute,
        Long tokenBudget,
        Long costBudgetMicros,
        Duration budgetWindow
) {
}
