package dev.gautamgupta.gateway.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gautamgupta.gateway.api.Usage;
import dev.gautamgupta.gateway.api.json.GatewayJson;
import org.junit.jupiter.api.Test;

class ChatCompletionRequestTest {

    private final ObjectMapper mapper = GatewayJson.create();

    @Test
    void readsSnakeCaseFieldsAndIgnoresUnknownOnes() throws Exception {
        String json = """
                {
                  "model": "gpt-4o-mini",
                  "service_tier": "auto",
                  "temperature": 0.2,
                  "max_tokens": 16,
                  "max_completion_tokens": 32,
                  "messages": [
                    {"role": "user", "content": "Hello"},
                    {"role": "user", "content": [{"type": "text", "text": "Hi"}]}
                  ]
                }
                """;

        ChatCompletionRequest request = mapper.readValue(json, ChatCompletionRequest.class);

        assertThat(request.model()).isEqualTo("gpt-4o-mini");
        assertThat(request.temperature()).isEqualTo(0.2);
        assertThat(request.maxTokens()).isEqualTo(16);
        assertThat(request.maxCompletionTokens()).isEqualTo(32);
        assertThat(request.messages().get(0).content().asText()).isEqualTo("Hello");
        assertThat(request.messages().get(1).content().isArray()).isTrue();

        String rewritten = mapper.writeValueAsString(request);
        assertThat(rewritten).contains("\"max_tokens\":16");
        assertThat(rewritten).doesNotContain("service_tier");
    }

    @Test
    void writesChatCompletionResponseInOpenAiShape() throws Exception {
        ChatCompletionResponse response = new ChatCompletionResponse(
                "chatcmpl-1",
                "chat.completion",
                1700000000L,
                "gpt-4o-mini",
                java.util.List.of(new ChatCompletionResponse.Choice(
                        0,
                        new ChatCompletionResponse.AssistantMessage(
                                "assistant",
                                mapper.getNodeFactory().textNode("Hi"),
                                null,
                                null),
                        "stop",
                        null)),
                new Usage(3, 1, 4),
                null);

        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"object\":\"chat.completion\"");
        assertThat(json).contains("\"finish_reason\":\"stop\"");
        assertThat(json).contains("\"prompt_tokens\":3");
        assertThat(json).doesNotContain("system_fingerprint");
    }
}
