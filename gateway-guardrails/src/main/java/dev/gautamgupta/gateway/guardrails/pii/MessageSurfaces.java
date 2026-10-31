package dev.gautamgupta.gateway.guardrails.pii;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest.ChatMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Text the pipeline scans: message content, the name field, and tool-call argument strings. */
final class MessageSurfaces {

    private MessageSurfaces() {
    }

    static long utf8Bytes(ChatCompletionRequest request) {
        if (request == null) {
            return 0L;
        }
        long total = 0L;
        for (ChatMessage message : request.messages()) {
            if (message == null) {
                continue;
            }
            total += utf8(message.name());
            total += contentBytes(message.content());
            total += toolBytes(message.toolCalls());
        }
        return total;
    }

    static ChatMessage rewrite(ChatMessage message, RedactionSession session) {
        if (message == null) {
            return null;
        }
        String name = message.name() == null ? null : session.rewrite(message.name());
        JsonNode content = rewriteContent(message.content(), session);
        JsonNode toolCalls = rewriteToolCalls(message.toolCalls(), session);
        if (same(name, message.name()) && content == message.content() && toolCalls == message.toolCalls()) {
            return message;
        }
        return new ChatMessage(message.role(), content, name, toolCalls, message.toolCallId());
    }

    private static JsonNode rewriteContent(JsonNode node, RedactionSession session) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isTextual()) {
            return rewriteText(node, session);
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            boolean changed = false;
            for (JsonNode child : node) {
                JsonNode next = rewritePart(child, session);
                copy.add(next);
                changed |= next != child;
            }
            return changed ? copy : node;
        }
        if (node.isObject()) {
            return rewritePart(node, session);
        }
        return node;
    }

    private static JsonNode rewritePart(JsonNode part, RedactionSession session) {
        if (part == null || part.isNull()) {
            return part;
        }
        if (part.isTextual()) {
            return rewriteText(part, session);
        }
        if (!part.isObject()) {
            return part;
        }
        JsonNode type = part.get("type");
        if (type != null && type.isTextual() && !"text".equals(type.asText())) {
            return part;
        }
        JsonNode text = part.get("text");
        if (text == null || !text.isTextual()) {
            return part;
        }
        JsonNode rewritten = rewriteText(text, session);
        if (rewritten == text) {
            return part;
        }
        ObjectNode copy = ((ObjectNode) part).deepCopy();
        copy.set("text", rewritten);
        return copy;
    }

    private static JsonNode rewriteText(JsonNode node, RedactionSession session) {
        String rewritten = session.rewrite(node.asText());
        if (rewritten.equals(node.asText())) {
            return node;
        }
        return TextNode.valueOf(rewritten);
    }

    private static JsonNode rewriteToolCalls(JsonNode toolCalls, RedactionSession session) {
        if (toolCalls == null || toolCalls.isNull() || !toolCalls.isArray()) {
            return toolCalls;
        }
        ArrayNode copy = JsonNodeFactory.instance.arrayNode();
        boolean changed = false;
        for (JsonNode call : toolCalls) {
            JsonNode next = rewriteToolCall(call, session);
            copy.add(next);
            changed |= next != call;
        }
        return changed ? copy : toolCalls;
    }

    private static JsonNode rewriteToolCall(JsonNode call, RedactionSession session) {
        if (call == null || !call.isObject()) {
            return call;
        }
        JsonNode function = call.get("function");
        if (function == null || !function.isObject()) {
            return call;
        }
        JsonNode arguments = function.get("arguments");
        if (arguments == null || !arguments.isTextual()) {
            return call;
        }
        JsonNode rewritten = rewriteText(arguments, session);
        if (rewritten == arguments) {
            return call;
        }
        ObjectNode functionCopy = ((ObjectNode) function).deepCopy();
        functionCopy.set("arguments", rewritten);
        ObjectNode callCopy = ((ObjectNode) call).deepCopy();
        callCopy.set("function", functionCopy);
        return callCopy;
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static long contentBytes(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0L;
        }
        if (node.isTextual()) {
            return utf8(node.asText());
        }
        long total = 0L;
        if (node.isArray()) {
            for (JsonNode child : node) {
                total += partBytes(child);
            }
            return total;
        }
        if (node.isObject()) {
            return partBytes(node);
        }
        return 0L;
    }

    private static long partBytes(JsonNode part) {
        if (part == null || part.isNull()) {
            return 0L;
        }
        if (part.isTextual()) {
            return utf8(part.asText());
        }
        if (!part.isObject()) {
            return 0L;
        }
        JsonNode type = part.get("type");
        if (type != null && type.isTextual() && !"text".equals(type.asText())) {
            return 0L;
        }
        JsonNode text = part.get("text");
        if (text != null && text.isTextual()) {
            return utf8(text.asText());
        }
        return 0L;
    }

    private static long toolBytes(JsonNode toolCalls) {
        if (toolCalls == null || !toolCalls.isArray()) {
            return 0L;
        }
        long total = 0L;
        for (JsonNode call : toolCalls) {
            if (call == null || !call.isObject()) {
                continue;
            }
            JsonNode function = call.get("function");
            if (function == null || !function.isObject()) {
                continue;
            }
            JsonNode arguments = function.get("arguments");
            if (arguments != null && arguments.isTextual()) {
                total += utf8(arguments.asText());
            }
        }
        return total;
    }

    private static long utf8(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    static List<ChatMessage> rewriteAll(List<ChatMessage> messages, RedactionSession session) {
        List<ChatMessage> rewritten = new ArrayList<>(messages.size());
        boolean changed = false;
        for (ChatMessage message : messages) {
            ChatMessage next = rewrite(message, session);
            rewritten.add(next);
            changed |= next != message;
        }
        return changed ? rewritten : messages;
    }
}
