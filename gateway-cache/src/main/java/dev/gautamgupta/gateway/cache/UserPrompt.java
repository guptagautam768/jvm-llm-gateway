package dev.gautamgupta.gateway.cache;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;

/** Text of the last {@code user} message. That is what the semantic cache embeds. */
public final class UserPrompt {

    private UserPrompt() {
    }

    public static String finalUserText(ChatCompletionRequest request) {
        if (request == null || request.messages().isEmpty()) {
            return null;
        }
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            ChatCompletionRequest.ChatMessage message = request.messages().get(i);
            if (message != null && "user".equals(message.role())) {
                return normalize(textOf(message.content()));
            }
        }
        return null;
    }

    private static String normalize(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String textOf(JsonNode content) {
        if (content == null || content.isNull()) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) {
            String piece = partText(part);
            if (piece == null || piece.isEmpty()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(piece);
        }
        return text.isEmpty() ? null : text.toString();
    }

    private static String partText(JsonNode part) {
        if (part == null || part.isNull()) {
            return null;
        }
        if (part.isTextual()) {
            return part.asText();
        }
        if (!part.isObject()) {
            return null;
        }
        JsonNode type = part.get("type");
        if (type != null && type.isTextual() && !"text".equals(type.asText())) {
            return null;
        }
        JsonNode text = part.get("text");
        if (text != null && text.isTextual()) {
            return text.asText();
        }
        return null;
    }
}
