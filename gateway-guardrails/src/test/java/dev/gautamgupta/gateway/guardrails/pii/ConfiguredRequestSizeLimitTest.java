package dev.gautamgupta.gateway.guardrails.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.TextNode;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfiguredRequestSizeLimitTest {

    @Test
    void rejectsWhenMessageTextExceedsTheLimit() {
        ConfiguredRequestSizeLimit limit = new ConfiguredRequestSizeLimit(8);
        ChatCompletionRequest request = request("hello");
        limit.check(request);

        assertThatThrownBy(() -> limit.check(request("hello!!!!")))
                .isInstanceOf(RequestTooLargeException.class)
                .hasMessageNotContaining("hello");
        RequestTooLargeException ex = catchTooLarge(request("hello!!!!"));
        assertThat(ex.bytes()).isEqualTo(9);
        assertThat(ex.limit()).isEqualTo(8);
        assertThat(ex.code()).isEqualTo("request_too_large");
    }

    @Test
    void aNonPositiveLimitIsDisabled() {
        new ConfiguredRequestSizeLimit(0).check(request("hello hello hello"));
        new ConfiguredRequestSizeLimit(-1).check(request("hello hello hello"));
    }

    @Test
    void countsUtf8BytesOfTextParts() {
        ConfiguredRequestSizeLimit limit = new ConfiguredRequestSizeLimit(4);
        ChatCompletionRequest request = request("héllo");
        assertThatThrownBy(() -> limit.check(request)).isInstanceOf(RequestTooLargeException.class);
    }

    private static RequestTooLargeException catchTooLarge(ChatCompletionRequest request) {
        try {
            new ConfiguredRequestSizeLimit(8).check(request);
        } catch (RequestTooLargeException ex) {
            return ex;
        }
        throw new AssertionError("expected RequestTooLargeException");
    }

    private static ChatCompletionRequest request(String content) {
        return new ChatCompletionRequest(
                "fast",
                List.of(new ChatMessage("user", TextNode.valueOf(content), null, null, null)),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
