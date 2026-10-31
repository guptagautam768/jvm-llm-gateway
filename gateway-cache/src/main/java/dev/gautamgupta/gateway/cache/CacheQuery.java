package dev.gautamgupta.gateway.cache;

import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;

/**
 * One eligible cache lookup. The request is the redacted body (ADR-0009). Streaming
 * and {@code X-Cache-Bypass} never build a query.
 */
public record CacheQuery(
        String tenantId,
        String route,
        String resolvedProvider,
        String resolvedModel,
        ChatCompletionRequest request,
        boolean semantic,
        double threshold
) {
}
