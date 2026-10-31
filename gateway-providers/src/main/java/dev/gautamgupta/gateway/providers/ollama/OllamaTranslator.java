package dev.gautamgupta.gateway.providers.ollama;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gautamgupta.gateway.api.Usage;
import dev.gautamgupta.gateway.api.chat.ChatCompletionChunk;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.api.chat.ChatCompletionResponse;
import dev.gautamgupta.gateway.api.embed.EmbeddingRequest;
import dev.gautamgupta.gateway.api.embed.EmbeddingResponse;
import dev.gautamgupta.gateway.core.provider.InvalidRequestException;
import dev.gautamgupta.gateway.core.provider.ProviderException;
import dev.gautamgupta.gateway.core.provider.ProviderIds;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * OpenAI chat and embeddings ↔ Ollama's native HTTP API.
 *
 * <p>Text chat is translated. {@code n} greater than 1 and non-text content are rejected.
 * Tools, penalties, and {@code response_format} are not forwarded (FR-001). Streaming
 * input is newline-delimited JSON, not SSE.
 */
public final class OllamaTranslator {

    static final String COMPLETION_ID = "chatcmpl-ollama";

    private final ObjectMapper objectMapper;

    public OllamaTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode chatBody(ChatCompletionRequest request, boolean stream) {
        if (request.n() != null && request.n() != 1) {
            throw new InvalidRequestException("Ollama accepts a single completion (n = 1).", "n");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model());
        body.put("stream", stream);
        ArrayNode messages = body.putArray("messages");
        List<ChatCompletionRequest.ChatMessage> source = request.messages();
        for (int i = 0; i < source.size(); i++) {
            ChatCompletionRequest.ChatMessage message = source.get(i);
            String role = message == null ? null : message.role();
            if (role == null || role.isBlank()) {
                throw new InvalidRequestException("message role is required.", "messages[" + i + "].role");
            }
            if (!"system".equals(role) && !"user".equals(role) && !"assistant".equals(role) && !"tool".equals(role)) {
                throw new InvalidRequestException("Unsupported message role.", "messages[" + i + "].role");
            }
            String text = text(message.content(), "messages[" + i + "].content");
            ObjectNode node = messages.addObject();
            node.put("role", role);
            node.put("content", text);
        }
        if (messages.isEmpty()) {
            throw new InvalidRequestException("messages is required.", "messages");
        }
        ObjectNode options = objectMapper.createObjectNode();
        if (request.temperature() != null) {
            options.put("temperature", request.temperature());
        }
        if (request.topP() != null) {
            options.put("top_p", request.topP());
        }
        Integer predict = request.maxCompletionTokens() != null ? request.maxCompletionTokens() : request.maxTokens();
        if (predict != null) {
            options.put("num_predict", predict);
        }
        ArrayNode stops = stops(request.stop());
        if (stops != null && !stops.isEmpty()) {
            options.set("stop", stops);
        }
        if (!options.isEmpty()) {
            body.set("options", options);
        }
        return body;
    }

    public ObjectNode embedBody(EmbeddingRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model());
        if (request.input() != null) {
            body.set("input", request.input());
        }
        if (request.dimensions() != null) {
            body.put("dimensions", request.dimensions());
        }
        return body;
    }

    public ChatCompletionResponse chatResponse(JsonNode body) {
        String model = textOrNull(body.path("model"));
        String content = body.path("message").path("content").asText("");
        String finish = finishReason(textOrNull(body.path("done_reason")));
        Usage usage = usage(body);
        ChatCompletionResponse.AssistantMessage message = new ChatCompletionResponse.AssistantMessage(
                "assistant",
                objectMapper.getNodeFactory().textNode(content),
                null,
                null);
        return new ChatCompletionResponse(
                COMPLETION_ID,
                "chat.completion",
                created(body),
                model,
                List.of(new ChatCompletionResponse.Choice(0, message, finish, null)),
                usage,
                null);
    }

    /**
     * One NDJSON object may carry a content delta, the terminal finish reason, or both.
     */
    public List<ChatCompletionChunk> chatChunks(JsonNode body, boolean includeUsage) {
        String content = body.path("message").path("content").asText("");
        boolean done = body.path("done").asBoolean(false);
        if (content.isEmpty() && !done) {
            return List.of();
        }
        String finish = done ? finishReason(textOrNull(body.path("done_reason"))) : null;
        Usage usage = done && includeUsage ? usage(body) : null;
        JsonNode deltaContent = content.isEmpty() ? null : objectMapper.getNodeFactory().textNode(content);
        String role = content.isEmpty() ? null : "assistant";
        ChatCompletionChunk chunk = new ChatCompletionChunk(
                COMPLETION_ID,
                "chat.completion.chunk",
                created(body),
                textOrNull(body.path("model")),
                List.of(new ChatCompletionChunk.Choice(
                        0,
                        new ChatCompletionChunk.Delta(role, deltaContent, null),
                        finish)),
                usage,
                null);
        return List.of(chunk);
    }

    public EmbeddingResponse embeddings(JsonNode body) {
        JsonNode vectors = body.get("embeddings");
        if (vectors == null || !vectors.isArray()) {
            JsonNode single = body.get("embedding");
            if (single != null && single.isArray()) {
                vectors = objectMapper.createArrayNode().add(single);
            }
        }
        if (vectors == null || !vectors.isArray() || vectors.isEmpty()) {
            throw ProviderException.malformed(ProviderIds.OLLAMA, "embeddings", null);
        }
        ArrayNode data = objectMapper.createArrayNode();
        for (int i = 0; i < vectors.size(); i++) {
            JsonNode vector = vectors.get(i);
            if (vector == null || !vector.isArray()) {
                throw ProviderException.malformed(ProviderIds.OLLAMA, "embeddings", null);
            }
            ObjectNode item = data.addObject();
            item.put("object", "embedding");
            item.put("index", i);
            item.set("embedding", vector);
        }
        int prompt = body.path("prompt_eval_count").asInt(0);
        Usage usage = new Usage(prompt, 0, prompt);
        return new EmbeddingResponse("list", data, textOrNull(body.path("model")), usage);
    }

    public String openAiError(String body, int status) {
        String message = "Ollama returned HTTP " + status;
        try {
            if (body != null && !body.isBlank()) {
                JsonNode node = objectMapper.readTree(body);
                JsonNode error = node.get("error");
                if (error != null && error.isTextual() && !error.asText().isBlank()) {
                    message = error.asText();
                } else if (error != null && error.hasNonNull("message")) {
                    message = error.get("message").asText();
                }
            }
        } catch (JacksonException ignored) {
            message = "Ollama returned HTTP " + status;
        }
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode error = root.putObject("error");
        error.put("message", message);
        error.put("type", status >= 500 || status == 429 ? "server_error" : "invalid_request_error");
        error.put("code", status == 404 ? "model_not_found" : "upstream_error");
        return root.toString();
    }

    private String text(JsonNode content, String param) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            throw new InvalidRequestException("Ollama accepts text content only.", param);
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : content) {
            if (part == null || part.isNull()) {
                continue;
            }
            if (part.isTextual()) {
                builder.append(part.asText());
                continue;
            }
            String type = part.path("type").asText("");
            if ((type.isEmpty() || "text".equals(type)) && part.hasNonNull("text")) {
                builder.append(part.get("text").asText());
                continue;
            }
            throw new InvalidRequestException("Ollama accepts text content only.", param);
        }
        return builder.toString();
    }

    private ArrayNode stops(JsonNode stop) {
        if (stop == null || stop.isNull()) {
            return null;
        }
        ArrayNode stops = objectMapper.createArrayNode();
        if (stop.isTextual()) {
            stops.add(stop.asText());
            return stops;
        }
        if (!stop.isArray()) {
            return null;
        }
        for (JsonNode item : stop) {
            if (item != null && item.isTextual()) {
                stops.add(item.asText());
            }
        }
        return stops;
    }

    private static Usage usage(JsonNode body) {
        if (!body.has("prompt_eval_count") && !body.has("eval_count")) {
            return null;
        }
        int prompt = body.path("prompt_eval_count").asInt(0);
        int completion = body.path("eval_count").asInt(0);
        return new Usage(prompt, completion, prompt + completion);
    }

    private static long created(JsonNode body) {
        String text = body.path("created_at").asText("");
        if (!text.isBlank()) {
            try {
                return Instant.parse(text).getEpochSecond();
            } catch (DateTimeParseException ignored) {
                return Instant.now().getEpochSecond();
            }
        }
        return Instant.now().getEpochSecond();
    }

    private static String finishReason(String doneReason) {
        if (doneReason == null || doneReason.isBlank() || "stop".equals(doneReason)) {
            return "stop";
        }
        if ("length".equals(doneReason)) {
            return "length";
        }
        return doneReason;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text.isBlank() ? null : text;
    }
}
