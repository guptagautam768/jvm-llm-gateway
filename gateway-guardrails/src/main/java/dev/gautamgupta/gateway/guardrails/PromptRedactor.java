package dev.gautamgupta.gateway.guardrails;

import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.guardrails.pii.RedactionResult;

/**
 * Redacts message content before the cache key, the embedding, and the provider body
 * are built (ADR-0009, NFR-004).
 *
 * <p>Callers run {@link RequestSizeLimit} first, then {@link #apply(ChatCompletionRequest, String)}.
 * The mapping is never stored, and implementations must not log the prompt.
 * A one-argument {@link #redact(ChatCompletionRequest)} stays for callers that only need the text.
 */
public interface PromptRedactor {

    ChatCompletionRequest redact(ChatCompletionRequest request);

    /**
     * Redact for this tenant. The default ignores the tenant and reports no counts,
     * which is what the week-4 no-op and test lambdas do.
     */
    default RedactionResult apply(ChatCompletionRequest request, String tenantId) {
        return RedactionResult.unchanged(redact(request));
    }
}
