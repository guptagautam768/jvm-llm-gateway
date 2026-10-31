package dev.gautamgupta.gateway.guardrails.pii;

import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.guardrails.RequestSizeLimit;

/**
 * FR-051, measured as the UTF-8 size of the text the redactor scans.
 * A non-positive limit disables the check. The codec cap is separate.
 */
public final class ConfiguredRequestSizeLimit implements RequestSizeLimit {

    private final long limitBytes;

    public ConfiguredRequestSizeLimit(long limitBytes) {
        this.limitBytes = limitBytes;
    }

    @Override
    public void check(ChatCompletionRequest request) {
        if (limitBytes <= 0 || request == null) {
            return;
        }
        long bytes = MessageSurfaces.utf8Bytes(request);
        if (bytes > limitBytes) {
            throw new RequestTooLargeException(bytes, limitBytes);
        }
    }
}
