package dev.gautamgupta.gateway.providers.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.api.json.GatewayJson;
import dev.gautamgupta.gateway.core.provider.InvalidRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class AnthropicMessageTranslatorTest {

    private final ObjectMapper mapper = GatewayJson.create();
    private final AnthropicMessageTranslator translator = new AnthropicMessageTranslator(mapper);

    @Test
    void translatesATextChatIntoTheMessagesApi() {
        ObjectNode body = translator.requestBody(request(), 4096, false);

        assertThat(body.get("model").asText()).isEqualTo("claude-haiku-4-5");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(32);
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.2);
        assertThat(body.get("system").asText()).isEqualTo("Be brief.");
        assertThat(body.get("messages")).hasSize(2);
        assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(body.get("messages").get(0).get("content").asText()).isEqualTo("Hello");
        assertThat(body.get("messages").get(1).get("role").asText()).isEqualTo("assistant");
        assertThat(body.get("stop_sequences").get(0).asText()).isEqualTo("END");
        assertThat(body.get("metadata").get("user_id").asText()).isEqualTo("user-7");
        assertThat(body.has("stream")).isFalse();
        assertThat(body.has("tools")).isFalse();
    }

    @Test
    void usesTheDefaultMaxTokensAndMarksAStream() {
        ChatCompletionRequest request = new ChatCompletionRequest(
                "claude-haiku-4-5",
                List.of(new ChatCompletionRequest.ChatMessage("user", mapper.getNodeFactory().textNode("Hi"), null, null, null)),
                null, null, null, true, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        ObjectNode body = translator.requestBody(request, 4096, true);

        assertThat(body.get("max_tokens").asInt()).isEqualTo(4096);
        assertThat(body.get("stream").asBoolean()).isTrue();
        assertThat(body.has("temperature")).isFalse();
    }

    @Test
    void mergesConsecutiveRolesAndJoinsSystemText() {
        ArrayNode parts = mapper.createArrayNode();
        parts.addObject().put("type", "text").put("text", "Hello ");
        parts.addObject().put("type", "text").put("text", "there");
        ChatCompletionRequest request = new ChatCompletionRequest(
                "claude-haiku-4-5",
                List.of(
                        new ChatCompletionRequest.ChatMessage("system", mapper.getNodeFactory().textNode("A"), null, null, null),
                        new ChatCompletionRequest.ChatMessage("system", mapper.getNodeFactory().textNode("B"), null, null, null),
                        new ChatCompletionRequest.ChatMessage("user", parts, null, null, null),
                        new ChatCompletionRequest.ChatMessage("user", mapper.getNodeFactory().textNode("Again"), null, null, null)),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        ObjectNode body = translator.requestBody(request, 100, false);

        assertThat(body.get("system").asText()).isEqualTo("A\n\nB");
        assertThat(body.get("messages")).hasSize(1);
        assertThat(body.get("messages").get(0).get("content").asText()).isEqualTo("Hello there\n\nAgain");
    }

    @Test
    void rejectsMultipleChoicesAndNonTextParts() {
        ChatCompletionRequest many = new ChatCompletionRequest(
                "claude-haiku-4-5",
                List.of(new ChatCompletionRequest.ChatMessage("user", mapper.getNodeFactory().textNode("Hi"), null, null, null)),
                null, null, 2, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> translator.requestBody(many, 100, false))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ex -> ((InvalidRequestException) ex).param())
                .isEqualTo("n");

        ArrayNode image = mapper.createArrayNode();
        image.addObject().put("type", "image_url");
        ChatCompletionRequest pictured = new ChatCompletionRequest(
                "claude-haiku-4-5",
                List.of(new ChatCompletionRequest.ChatMessage("user", image, null, null, null)),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> translator.requestBody(pictured, 100, false))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void translatesAMessageResponse() throws Exception {
        var response = translator.response(mapper.readTree("""
                {
                  "id": "msg_1",
                  "type": "message",
                  "role": "assistant",
                  "model": "claude-haiku-4-5",
                  "content": [{"type": "text", "text": "Hi there"}],
                  "stop_reason": "end_turn",
                  "usage": {"input_tokens": 3, "output_tokens": 2}
                }
                """));

        assertThat(response.id()).isEqualTo("msg_1");
        assertThat(response.object()).isEqualTo("chat.completion");
        assertThat(response.choices().get(0).message().content().asText()).isEqualTo("Hi there");
        assertThat(response.choices().get(0).finishReason()).isEqualTo("stop");
        assertThat(response.usage().promptTokens()).isEqualTo(3);
        assertThat(response.usage().completionTokens()).isEqualTo(2);
        assertThat(response.usage().totalTokens()).isEqualTo(5);
        assertThat(AnthropicMessageTranslator.finishReason("max_tokens")).isEqualTo("length");
        assertThat(AnthropicMessageTranslator.finishReason("tool_use")).isEqualTo("tool_calls");
    }

    @Test
    void translatesStreamEventsIntoOpenAiChunks() throws Exception {
        StepVerifier.create(translator.chunks(reactor.core.publisher.Flux.just(
                """
                {"type":"message_start","message":{"id":"msg_1","model":"claude-haiku-4-5","role":"assistant","usage":{"input_tokens":4,"output_tokens":1}}}
                """,
                """
                {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}
                """,
                """
                {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}
                """,
                """
                {"type":"message_stop"}
                """), true))
                .assertNext(chunk -> {
                    assertThat(chunk.id()).isEqualTo("msg_1");
                    assertThat(chunk.object()).isEqualTo("chat.completion.chunk");
                    assertThat(chunk.choices().get(0).delta().role()).isEqualTo("assistant");
                })
                .assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content().asText()).isEqualTo("Hi"))
                .assertNext(chunk -> {
                    assertThat(chunk.choices().get(0).finishReason()).isEqualTo("stop");
                    assertThat(chunk.usage().totalTokens()).isEqualTo(6);
                })
                .verifyComplete();
    }

    private ChatCompletionRequest request() {
        return new ChatCompletionRequest(
                "claude-haiku-4-5",
                List.of(
                        new ChatCompletionRequest.ChatMessage("system", mapper.getNodeFactory().textNode("Be brief."), null, null, null),
                        new ChatCompletionRequest.ChatMessage("user", mapper.getNodeFactory().textNode("Hello"), null, null, null),
                        new ChatCompletionRequest.ChatMessage("assistant", mapper.getNodeFactory().textNode("Yes?"), null, null, null)),
                0.2,
                null,
                null,
                false,
                null,
                mapper.getNodeFactory().textNode("END"),
                32,
                null,
                null,
                null,
                "user-7",
                null,
                null,
                mapper.createObjectNode().put("type", "function"),
                null,
                null,
                null,
                null);
    }
}
