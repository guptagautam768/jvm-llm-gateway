package dev.gautamgupta.gateway.providers.anthropic;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gautamgupta.gateway.api.Usage;
import dev.gautamgupta.gateway.api.chat.ChatCompletionChunk;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.api.chat.ChatCompletionResponse;
import dev.gautamgupta.gateway.core.provider.InvalidRequestException;
import dev.gautamgupta.gateway.core.provider.ProviderException;
import dev.gautamgupta.gateway.core.provider.ProviderIds;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * OpenAI chat completions ↔ Anthropic Messages API.
 *
 * <p>Text chat is translated. Tools, penalties, logprobs, and {@code n} other than 1
 * are not sent: {@code n} greater than 1 is rejected, and the other fields are ignored
 * (FR-001). Anthropic requires {@code max_tokens}; when the client omits both token
 * limits the adapter sends the configured default.
 */
public final class AnthropicMessageTranslator {

    private final ObjectMapper objectMapper;

    public AnthropicMessageTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode requestBody(ChatCompletionRequest request, int defaultMaxTokens, boolean stream) {
        if (request.n() != null && request.n() != 1) {
            throw new InvalidRequestException("Anthropic accepts a single completion (n = 1).", "n");
        }
        List<ChatCompletionRequest.ChatMessage> messages = request.messages();
        List<String> system = new ArrayList<>();
        List<Turn> turns = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            ChatCompletionRequest.ChatMessage message = messages.get(i);
            String role = message.role();
            String text = text(message.content(), "messages[" + i + "].content");
            if ("system".equals(role)) {
                if (!text.isBlank()) {
                    system.add(text);
                }
                continue;
            }
            String anthropicRole = switch (role) {
                case "user", "assistant" -> role;
                case "tool" -> "user";
                default -> throw new InvalidRequestException("Unsupported message role.", "messages[" + i + "].role");
            };
            if (text.isBlank()) {
                continue;
            }
            if (!turns.isEmpty() && turns.get(turns.size() - 1).role().equals(anthropicRole)) {
                Turn previous = turns.remove(turns.size() - 1);
                turns.add(new Turn(anthropicRole, previous.text() + "\n\n" + text));
            } else {
                turns.add(new Turn(anthropicRole, text));
            }
        }
        if (turns.isEmpty()) {
            throw new InvalidRequestException("messages must include a user or assistant turn.", "messages");
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model());
        int maxTokens = request.maxCompletionTokens() != null
                ? request.maxCompletionTokens()
                : request.maxTokens() != null ? request.maxTokens() : defaultMaxTokens;
        body.put("max_tokens", maxTokens);
        if (!system.isEmpty()) {
            body.put("system", String.join("\n\n", system));
        }
        ArrayNode messageNodes = body.putArray("messages");
        for (Turn turn : turns) {
            ObjectNode node = messageNodes.addObject();
            node.put("role", turn.role());
            node.put("content", turn.text());
        }
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (request.topP() != null) {
            body.put("top_p", request.topP());
        }
        ArrayNode stops = stopSequences(request.stop());
        if (stops != null && !stops.isEmpty()) {
            body.set("stop_sequences", stops);
        }
        if (request.user() != null && !request.user().isBlank()) {
            body.putObject("metadata").put("user_id", request.user());
        }
        if (stream) {
            body.put("stream", true);
        }
        return body;
    }

    public ChatCompletionResponse response(JsonNode body) {
        String id = body.path("id").asText("msg_anthropic");
        String model = body.path("model").asText(null);
        String text = contentText(body.path("content"));
        JsonNode toolCalls = toolCalls(body.path("content"));
        String finish = finishReason(body.path("stop_reason").asText(null));
        Usage usage = usage(body.path("usage"), null);
        long created = Instant.now().getEpochSecond();
        ChatCompletionResponse.AssistantMessage message = new ChatCompletionResponse.AssistantMessage(
                "assistant",
                text == null ? null : objectMapper.getNodeFactory().textNode(text),
                toolCalls,
                null);
        return new ChatCompletionResponse(
                id,
                "chat.completion",
                created,
                model,
                List.of(new ChatCompletionResponse.Choice(0, message, finish, null)),
                usage,
                null);
    }

    public Flux<ChatCompletionChunk> chunks(Flux<String> events, boolean includeUsage) {
        return Flux.defer(() -> {
            StreamAssembler assembler = new StreamAssembler(includeUsage);
            return events.concatMap(assembler::accept);
        });
    }

    private ArrayNode stopSequences(JsonNode stop) {
        if (stop == null || stop.isNull()) {
            return null;
        }
        ArrayNode array = objectMapper.createArrayNode();
        if (stop.isTextual()) {
            array.add(stop.asText());
            return array;
        }
        if (stop.isArray()) {
            for (JsonNode item : stop) {
                if (item.isTextual()) {
                    array.add(item.asText());
                }
            }
            return array;
        }
        return null;
    }

    private String text(JsonNode content, String param) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode part : content) {
                if (part.isTextual()) {
                    builder.append(part.asText());
                    continue;
                }
                if ("text".equals(part.path("type").asText()) && part.hasNonNull("text")) {
                    builder.append(part.get("text").asText());
                    continue;
                }
                throw new InvalidRequestException(
                        "Only text message content is supported for Anthropic.",
                        param);
            }
            return builder.toString();
        }
        throw new InvalidRequestException("Only text message content is supported for Anthropic.", param);
    }

    private static String contentText(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : content) {
            if ("text".equals(part.path("type").asText()) && part.has("text")) {
                builder.append(part.get("text").asText(""));
            }
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private JsonNode toolCalls(JsonNode content) {
        if (content == null || !content.isArray()) {
            return null;
        }
        ArrayNode calls = objectMapper.createArrayNode();
        for (JsonNode part : content) {
            if (!"tool_use".equals(part.path("type").asText())) {
                continue;
            }
            ObjectNode call = calls.addObject();
            call.put("id", part.path("id").asText(null));
            call.put("type", "function");
            ObjectNode function = call.putObject("function");
            function.put("name", part.path("name").asText(""));
            JsonNode input = part.get("input");
            function.put("arguments", input == null || input.isNull() ? "{}" : input.toString());
        }
        return calls.isEmpty() ? null : calls;
    }

    static String finishReason(String stopReason) {
        if (stopReason == null || stopReason.isBlank()) {
            return null;
        }
        return switch (stopReason) {
            case "end_turn", "stop_sequence", "pause_turn" -> "stop";
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            case "refusal" -> "content_filter";
            default -> stopReason;
        };
    }

    private static Usage usage(JsonNode usage, Integer outputOverride) {
        if ((usage == null || usage.isMissingNode() || usage.isNull()) && outputOverride == null) {
            return null;
        }
        Integer input = usage != null && usage.hasNonNull("input_tokens") ? usage.get("input_tokens").asInt() : null;
        Integer output = outputOverride != null
                ? outputOverride
                : usage != null && usage.hasNonNull("output_tokens") ? usage.get("output_tokens").asInt() : null;
        if (input == null && output == null) {
            return null;
        }
        int prompt = input == null ? 0 : input;
        int completion = output == null ? 0 : output;
        return new Usage(input, output, prompt + completion);
    }

    private record Turn(String role, String text) {
    }

    private final class StreamAssembler {
        private final boolean includeUsage;
        private final long created = Instant.now().getEpochSecond();
        private String id = "msg_anthropic";
        private String model;
        private Integer inputTokens;

        private StreamAssembler(boolean includeUsage) {
            this.includeUsage = includeUsage;
        }

        private Flux<ChatCompletionChunk> accept(String data) {
            String trimmed = data == null ? "" : data.trim();
            if (trimmed.isEmpty() || "[DONE]".equals(trimmed)) {
                return Flux.empty();
            }
            try {
                JsonNode event = objectMapper.readTree(trimmed);
                String type = event.path("type").asText("");
                return switch (type) {
                    case "message_start" -> Flux.just(messageStart(event));
                    case "content_block_delta" -> contentDelta(event);
                    case "message_delta" -> Flux.just(messageDelta(event));
                    case "error" -> Flux.error(streamError(event));
                    default -> Flux.empty();
                };
            } catch (JacksonException | IllegalArgumentException ex) {
                return Flux.error(ProviderException.malformed(ProviderIds.ANTHROPIC, "stream chunk", ex));
            }
        }

        private ChatCompletionChunk messageStart(JsonNode event) {
            JsonNode message = event.path("message");
            if (message.hasNonNull("id")) {
                id = message.get("id").asText();
            }
            if (message.hasNonNull("model")) {
                model = message.get("model").asText();
            }
            if (message.path("usage").hasNonNull("input_tokens")) {
                inputTokens = message.path("usage").get("input_tokens").asInt();
            }
            return chunk(
                    List.of(new ChatCompletionChunk.Choice(
                            0,
                            new ChatCompletionChunk.Delta("assistant", null, null),
                            null)),
                    null);
        }

        private Flux<ChatCompletionChunk> contentDelta(JsonNode event) {
            JsonNode delta = event.path("delta");
            if (!"text_delta".equals(delta.path("type").asText()) || !delta.has("text")) {
                return Flux.empty();
            }
            return Flux.just(chunk(
                    List.of(new ChatCompletionChunk.Choice(
                            0,
                            new ChatCompletionChunk.Delta(
                                    null,
                                    objectMapper.getNodeFactory().textNode(delta.get("text").asText()),
                                    null),
                            null)),
                    null));
        }

        private ChatCompletionChunk messageDelta(JsonNode event) {
            String finish = finishReason(event.path("delta").path("stop_reason").asText(null));
            Usage usage = null;
            if (includeUsage) {
                Integer output = event.path("usage").hasNonNull("output_tokens")
                        ? event.path("usage").get("output_tokens").asInt()
                        : null;
                if (inputTokens != null || output != null) {
                    int prompt = inputTokens == null ? 0 : inputTokens;
                    int completion = output == null ? 0 : output;
                    usage = new Usage(inputTokens, output, prompt + completion);
                }
            }
            return chunk(
                    List.of(new ChatCompletionChunk.Choice(
                            0,
                            new ChatCompletionChunk.Delta(null, null, null),
                            finish)),
                    usage);
        }

        private ProviderException streamError(JsonNode event) {
            String message = event.path("error").path("message").asText("Anthropic stream failed");
            ObjectNode body = objectMapper.createObjectNode();
            ObjectNode error = body.putObject("error");
            error.put("message", message);
            error.put("type", "upstream_error");
            error.put("code", event.path("error").path("type").asText("api_error"));
            return ProviderException.upstream(ProviderIds.ANTHROPIC, 502, body.toString(), null);
        }

        private ChatCompletionChunk chunk(List<ChatCompletionChunk.Choice> choices, Usage usage) {
            return new ChatCompletionChunk(id, "chat.completion.chunk", created, model, choices, usage, null);
        }
    }
}
