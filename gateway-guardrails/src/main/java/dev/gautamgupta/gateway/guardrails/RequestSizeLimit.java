package dev.gautamgupta.gateway.guardrails;

import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;

/**
 * First step of the ADR-0009 pipeline, before {@link PromptRedactor}.
 *
 * <p>An oversized body throws {@link dev.gautamgupta.gateway.guardrails.pii.RequestTooLargeException}
 * (HTTP 413). The week-4 no-op does not.
 */
public interface RequestSizeLimit {

    void check(ChatCompletionRequest request);
}
