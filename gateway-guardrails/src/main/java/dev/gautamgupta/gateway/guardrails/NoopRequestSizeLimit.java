package dev.gautamgupta.gateway.guardrails;

import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;

/** Size-limit double that does not reject. Production uses {@code ConfiguredRequestSizeLimit}. */
public final class NoopRequestSizeLimit implements RequestSizeLimit {

    @Override
    public void check(ChatCompletionRequest request) {
    }
}
