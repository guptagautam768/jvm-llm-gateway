package dev.gautamgupta.gateway.guardrails;

import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;

/** Week 4 size-limit hook. Does not reject. */
public final class NoopRequestSizeLimit implements RequestSizeLimit {

    @Override
    public void check(ChatCompletionRequest request) {
        // FR-051 lands in week 5. The call site is already in front of redaction and the cache.
    }
}
