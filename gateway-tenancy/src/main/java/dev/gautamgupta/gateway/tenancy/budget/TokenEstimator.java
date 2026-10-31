package dev.gautamgupta.gateway.tenancy.budget;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;

/**
 * Rough prompt size used only for the pre-flight budget check. About four characters
 * per token. Completion is {@code max_completion_tokens}, else {@code max_tokens}, else
 * the configured default. The post-call reconcile replaces this with provider usage.
 */
public final class TokenEstimator {

    private final int defaultCompletionTokens;

    public TokenEstimator(int defaultCompletionTokens) {
        this.defaultCompletionTokens = Math.max(0, defaultCompletionTokens);
    }

    public UsageEstimate estimate(ChatCompletionRequest request) {
        int prompt = 0;
        for (ChatCompletionRequest.ChatMessage message : request.messages()) {
            if (message != null) {
                prompt += tokens(message.content());
            }
        }
        if (prompt < 1) {
            prompt = 1;
        }
        int completion = defaultCompletionTokens;
        if (request.maxCompletionTokens() != null) {
            completion = Math.max(0, request.maxCompletionTokens());
        } else if (request.maxTokens() != null) {
            completion = Math.max(0, request.maxTokens());
        }
        return new UsageEstimate(prompt, completion);
    }

    /** Embeddings reserve no completion tokens. At least one prompt token is charged. */
    public UsageEstimate estimateInputs(java.util.List<String> texts) {
        int prompt = 0;
        if (texts != null) {
            for (String text : texts) {
                prompt += charsToTokens(text);
            }
        }
        if (prompt < 1) {
            prompt = 1;
        }
        return new UsageEstimate(prompt, 0);
    }

    static int tokens(JsonNode content) {
        if (content == null || content.isNull()) {
            return 0;
        }
        if (content.isTextual()) {
            return charsToTokens(content.asText());
        }
        if (content.isArray()) {
            int sum = 0;
            for (JsonNode part : content) {
                if (part != null && part.hasNonNull("text")) {
                    sum += charsToTokens(part.get("text").asText());
                } else if (part != null) {
                    sum += charsToTokens(part.toString());
                }
            }
            return sum;
        }
        return charsToTokens(content.toString());
    }

    static int charsToTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (text.length() + 3) / 4;
    }
}
