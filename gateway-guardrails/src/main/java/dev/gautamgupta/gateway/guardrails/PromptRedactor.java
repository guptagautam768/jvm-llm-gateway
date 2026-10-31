package dev.gautamgupta.gateway.guardrails;

import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;

/**
 * Redacts message content before the cache key, the embedding, and the provider body
 * are built (ADR-0009, NFR-004).
 *
 * <p>Week 4 ships {@link NoopPromptRedactor}. Week 5 replaces that bean with the
 * detector pipeline. The mapping is never stored, and this method must not log the
 * prompt. Callers run {@link RequestSizeLimit} first.
 */
public interface PromptRedactor {

    ChatCompletionRequest redact(ChatCompletionRequest request);
}
