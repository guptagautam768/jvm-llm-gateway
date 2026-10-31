package dev.gautamgupta.gateway.cache;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * Canonical JSON hashed into the exact cache key.
 *
 * <p>The document is route, resolved provider, resolved model, messages, and the
 * generation fields that change the completion. {@code user} and {@code stream} are
 * omitted. Object keys are sorted and nulls are dropped so two equivalent requests
 * hash the same. The context hash is the same document with the final user text
 * blanked, and it is the semantic TAG that keeps a similar question under a different
 * system prompt from hitting.
 */
public final class CanonicalRequest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CanonicalRequest() {
    }

    public record Digests(String exactHash, String contextHash) {
    }

    public static Digests digest(
            String route,
            String provider,
            String model,
            ChatCompletionRequest request
    ) {
        ObjectNode root = tree(route, provider, model, request);
        String exact = sha256(write(root));
        ObjectNode context = root.deepCopy();
        blankFinalUserContent(context);
        return new Digests(exact, sha256(write(context)));
    }

    private static ObjectNode tree(String route, String provider, String model, ChatCompletionRequest request) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("route", route == null ? "" : route);
        root.put("resolved_provider", provider == null ? "" : provider);
        root.put("resolved_model", model == null ? "" : model);
        root.set("messages", messages(request));
        putDouble(root, "temperature", request.temperature());
        putDouble(root, "top_p", request.topP());
        putInt(root, "n", request.n());
        if (request.stop() != null && !request.stop().isNull()) {
            root.set("stop", request.stop());
        }
        putInt(root, "max_tokens", request.maxTokens());
        putInt(root, "max_completion_tokens", request.maxCompletionTokens());
        putDouble(root, "presence_penalty", request.presencePenalty());
        putDouble(root, "frequency_penalty", request.frequencyPenalty());
        if (request.responseFormat() != null && !request.responseFormat().isNull()) {
            root.set("response_format", request.responseFormat());
        }
        putInt(root, "seed", request.seed());
        if (request.tools() != null && !request.tools().isNull()) {
            root.set("tools", request.tools());
        }
        if (request.toolChoice() != null && !request.toolChoice().isNull()) {
            root.set("tool_choice", request.toolChoice());
        }
        if (request.parallelToolCalls() != null) {
            root.put("parallel_tool_calls", request.parallelToolCalls());
        }
        if (request.logprobs() != null) {
            root.put("logprobs", request.logprobs());
        }
        putInt(root, "top_logprobs", request.topLogprobs());
        return root;
    }

    private static ArrayNode messages(ChatCompletionRequest request) {
        ArrayNode array = MAPPER.createArrayNode();
        for (ChatCompletionRequest.ChatMessage message : request.messages()) {
            if (message == null) {
                continue;
            }
            ObjectNode node = MAPPER.createObjectNode();
            if (message.role() != null) {
                node.put("role", message.role());
            }
            if (message.content() != null && !message.content().isNull()) {
                node.set("content", message.content());
            }
            if (message.name() != null) {
                node.put("name", message.name());
            }
            if (message.toolCalls() != null && !message.toolCalls().isNull()) {
                node.set("tool_calls", message.toolCalls());
            }
            if (message.toolCallId() != null) {
                node.put("tool_call_id", message.toolCallId());
            }
            array.add(node);
        }
        return array;
    }

    private static void blankFinalUserContent(ObjectNode root) {
        JsonNode messages = root.get("messages");
        if (messages == null || !messages.isArray()) {
            return;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode message = messages.get(i);
            if (message instanceof ObjectNode object && "user".equals(text(object.get("role")))) {
                object.put("content", "");
                return;
            }
        }
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static void putDouble(ObjectNode root, String field, Double value) {
        if (value != null) {
            root.put(field, value);
        }
    }

    private static void putInt(ObjectNode root, String field, Integer value) {
        if (value != null) {
            root.put(field, value);
        }
    }

    private static byte[] write(JsonNode node) {
        try {
            return MAPPER.writeValueAsBytes(sort(node));
        } catch (JacksonException ex) {
            throw new IllegalStateException("canonical cache key could not be encoded", ex);
        }
    }

    private static JsonNode sort(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String name : names) {
                JsonNode child = sort(node.get(name));
                if (child != null && !child.isNull()) {
                    sorted.set(name, child);
                }
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> array.add(sort(child)));
            return array;
        }
        return node;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
