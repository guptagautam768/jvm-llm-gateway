package dev.gautamgupta.gateway.guardrails.pii;

import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import java.util.Map;

/**
 * Redacted request plus occurrence counts by metric type.
 *
 * <p>{@code rejected} means the caller must not cache or forward the request.
 * Counts never contain the matched text.
 */
public record RedactionResult(
        ChatCompletionRequest request,
        Map<String, Integer> counts,
        boolean rejected
) {

    public RedactionResult {
        counts = counts == null || counts.isEmpty() ? Map.of() : Map.copyOf(counts);
    }

    public static RedactionResult unchanged(ChatCompletionRequest request) {
        return new RedactionResult(request, Map.of(), false);
    }
}
