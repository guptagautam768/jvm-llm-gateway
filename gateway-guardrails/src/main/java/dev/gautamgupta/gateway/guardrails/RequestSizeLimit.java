package dev.gautamgupta.gateway.guardrails;

import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;

/**
 * First step of the ADR-0009 pipeline, before {@link PromptRedactor}.
 *
 * <p>Week 5 rejects an oversized body with HTTP 413. Week 4 does not enforce a limit.
 * An implementation may throw a runtime exception; the week 4 bean does not.
 */
public interface RequestSizeLimit {

    void check(ChatCompletionRequest request);
}
