package dev.gautamgupta.gateway.core.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.gautamgupta.gateway.core.provider.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** OpenAI embeddings {@code input}: one string, or an array of strings (API-021). */
public final class EmbeddingInputs {

    private EmbeddingInputs() {
    }

    public static List<String> texts(JsonNode input) {
        if (input == null || input.isNull()) {
            throw new InvalidRequestException("input is required.", "input");
        }
        if (input.isTextual()) {
            return List.of(input.asText());
        }
        if (!input.isArray()) {
            throw new InvalidRequestException("input must be a string or an array of strings.", "input");
        }
        if (input.isEmpty()) {
            throw new InvalidRequestException("input must not be empty.", "input");
        }
        List<String> texts = new ArrayList<>(input.size());
        for (int i = 0; i < input.size(); i++) {
            JsonNode item = input.get(i);
            if (item == null || !item.isTextual()) {
                throw new InvalidRequestException("input items must be strings.", "input[" + i + "]");
            }
            texts.add(item.asText());
        }
        return List.copyOf(texts);
    }

    public static long utf8Bytes(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (String text : texts) {
            if (text != null) {
                total += text.getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return total;
    }

    /** Preserve a single string as a string. An array stays an array, including length 1. */
    public static JsonNode node(JsonNodeFactory factory, List<String> texts, boolean array) {
        JsonNodeFactory nodes = factory == null ? JsonNodeFactory.instance : factory;
        if (!array) {
            String text = texts == null || texts.isEmpty() ? "" : texts.get(0);
            return TextNode.valueOf(text);
        }
        ArrayNode written = nodes.arrayNode();
        if (texts != null) {
            for (String text : texts) {
                written.add(text);
            }
        }
        return written;
    }
}
