package dev.gautamgupta.gateway.guardrails;

import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;

/** Week 4 redaction hook. Returns the request unchanged. */
public final class NoopPromptRedactor implements PromptRedactor {

    @Override
    public ChatCompletionRequest redact(ChatCompletionRequest request) {
        return request;
    }
}
