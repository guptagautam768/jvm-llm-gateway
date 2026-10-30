package dev.gautamgupta.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CacheRulesTest {

    @Test
    void exactKeyIsTenantScopedAndIgnoresUserAndStream() {
        ChatCompletionRequest left = request("Hello", 0.0, "alice");
        ChatCompletionRequest right = left.withStream(true);
        CanonicalRequest.Digests first = CanonicalRequest.digest("fast", "openai", "gpt-4o-mini", left);
        CanonicalRequest.Digests second = CanonicalRequest.digest("fast", "openai", "gpt-4o-mini", right);
        CanonicalRequest.Digests otherUser = CanonicalRequest.digest(
                "fast", "openai", "gpt-4o-mini", request("Hello", 0.0, "bob"));

        assertThat(first.exactHash()).isEqualTo(second.exactHash()).isEqualTo(otherUser.exactHash());
        assertThat(ExactCacheKey.redisKey("acme", first.exactHash()))
                .isEqualTo("cache:x:acme:" + first.exactHash())
                .isNotEqualTo(ExactCacheKey.redisKey("other", first.exactHash()));
        assertThat(ExactCacheKey.redisKey("other", first.exactHash())).endsWith(first.exactHash());
    }

    @Test
    void messageTextAndTemperatureChangeTheHashButKeyOrderDoesNot() {
        ChatCompletionRequest plain = request("Hello", 0.0, null);
        ChatCompletionRequest warmer = request("Hello", 0.2, null);
        ChatCompletionRequest other = request("Hello!", 0.0, null);
        ObjectNode format = JsonNodeFactory.instance.objectNode();
        format.put("b", 1);
        format.put("a", 2);
        ObjectNode reversed = JsonNodeFactory.instance.objectNode();
        reversed.put("a", 2);
        reversed.put("b", 1);
        ChatCompletionRequest first = withFormat(plain, format);
        ChatCompletionRequest second = withFormat(plain, reversed);

        CanonicalRequest.Digests base = CanonicalRequest.digest("fast", "openai", "gpt-4o-mini", plain);
        assertThat(CanonicalRequest.digest("fast", "openai", "gpt-4o-mini", warmer).exactHash())
                .isNotEqualTo(base.exactHash());
        assertThat(CanonicalRequest.digest("fast", "openai", "gpt-4o-mini", other).exactHash())
                .isNotEqualTo(base.exactHash());
        assertThat(CanonicalRequest.digest("fast", "openai", "gpt-4o-mini", first).exactHash())
                .isEqualTo(CanonicalRequest.digest("fast", "openai", "gpt-4o-mini", second).exactHash());
    }

    @Test
    void contextHashIgnoresTheFinalUserTextOnly() {
        CanonicalRequest.Digests hello = CanonicalRequest.digest("fast", "openai", "gpt-4o-mini", request("Hello", 0.0, null));
        CanonicalRequest.Digests goodbye = CanonicalRequest.digest("fast", "openai", "gpt-4o-mini", request("Goodbye", 0.0, null));
        CanonicalRequest.Digests warmer = CanonicalRequest.digest("fast", "openai", "gpt-4o-mini", request("Hello", 0.2, null));

        assertThat(hello.exactHash()).isNotEqualTo(goodbye.exactHash());
        assertThat(hello.contextHash()).isEqualTo(goodbye.contextHash());
        assertThat(hello.contextHash()).isNotEqualTo(warmer.contextHash());
    }

    @Test
    void redactedTextChangesTheExactKey() {
        ChatCompletionRequest raw = request("ada@example.com", 0.0, null);
        ChatCompletionRequest redacted = request("[EMAIL_1]", 0.0, null);
        assertThat(CanonicalRequest.digest("fast", "openai", "gpt-4o-mini", raw).exactHash())
                .isNotEqualTo(CanonicalRequest.digest("fast", "openai", "gpt-4o-mini", redacted).exactHash());
    }

    @Test
    void bypassAcceptsOnlyTrue() {
        assertThat(CacheBypass.requested("true")).isTrue();
        assertThat(CacheBypass.requested(" TRUE ")).isTrue();
        assertThat(CacheBypass.requested("false")).isFalse();
        assertThat(CacheBypass.requested("1")).isFalse();
        assertThat(CacheBypass.requested("yes")).isFalse();
        assertThat(CacheBypass.requested(null)).isFalse();
    }

    @Test
    void omittedTemperatureIsNotSemanticAndLowTemperatureIs() {
        assertThat(SemanticEligibility.allowed(null, SemanticThreshold.MAX_TEMPERATURE)).isFalse();
        assertThat(SemanticEligibility.allowed(0.2d, SemanticThreshold.MAX_TEMPERATURE)).isTrue();
        assertThat(SemanticEligibility.allowed(0.0d, SemanticThreshold.MAX_TEMPERATURE)).isTrue();
        assertThat(SemanticEligibility.allowed(0.21d, SemanticThreshold.MAX_TEMPERATURE)).isFalse();
    }

    @Test
    void defaultThresholdSeparatesNearDuplicatesFromRelatedPrompts() {
        float[] base = unit(0);
        float[] near = unit(Math.toRadians(15));
        float[] related = unit(Math.toRadians(30));

        double nearSimilarity = Cosine.similarity(base, near);
        double relatedSimilarity = Cosine.similarity(base, related);
        assertThat(nearSimilarity).isBetween(0.96d, 0.97d);
        assertThat(relatedSimilarity).isBetween(0.86d, 0.87d);
        assertThat(Cosine.hits(nearSimilarity, SemanticThreshold.DEFAULT)).isTrue();
        assertThat(Cosine.hits(relatedSimilarity, SemanticThreshold.DEFAULT)).isFalse();
        assertThat(Cosine.hits(Cosine.fromDistance(0.0d), SemanticThreshold.DEFAULT)).isTrue();
        assertThat(Cosine.hits(0.80d, SemanticThreshold.DEFAULT)).isFalse();
    }

    @Test
    void tagEscapeCoversHyphensAndTheVectorBlobIsLittleEndian() {
        assertThat(TagValue.escape("gpt-4o-mini")).isEqualTo("gpt\\-4o\\-mini");
        assertThat(TagValue.escape("acme")).isEqualTo("acme");
        byte[] blob = VectorBytes.toBytes(new float[] {1.0f});
        assertThat(blob).containsExactly(0, 0, (byte) 0x80, 0x3f);
        assertThat(new String(blob, StandardCharsets.ISO_8859_1)).hasSize(4);
    }

    @Test
    void finalUserTextReadsTheLastUserMessage() {
        ChatCompletionRequest request = new ChatCompletionRequest(
                "gpt-4o-mini",
                List.of(
                        message("system", "Be brief"),
                        message("user", "first"),
                        message("assistant", "ok"),
                        new ChatCompletionRequest.ChatMessage("user", textParts("Paris", "please"), null, null, null)),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(UserPrompt.finalUserText(request)).isEqualTo("Paris\nplease");
        assertThat(UserPrompt.finalUserText(request("   ", null, null))).isNull();
    }

    @Test
    void searchReplyReadsPayloadAndDistance() {
        byte[] payload = "{\"provider\":\"openai\"}".getBytes(StandardCharsets.UTF_8);
        List<Object> reply = List.of(
                1L,
                "cache:s:acme:abc".getBytes(StandardCharsets.UTF_8),
                List.of(
                        "payload".getBytes(StandardCharsets.UTF_8),
                        payload,
                        "dist".getBytes(StandardCharsets.UTF_8),
                        "0.05".getBytes(StandardCharsets.UTF_8)));
        SearchReply.Result parsed = SearchReply.parse(reply);
        assertThat(parsed).isNotNull();
        assertThat(parsed.distance()).isEqualTo(0.05d);
        assertThat(parsed.payload()).isEqualTo(payload);
        assertThat(SearchReply.parse(List.of(0L))).isNull();

        byte[] key = "cache:s:acme:abc".getBytes(StandardCharsets.UTF_8);
        List<Object> dialect2 = List.of(
                "total_results".getBytes(StandardCharsets.UTF_8),
                1L,
                "results".getBytes(StandardCharsets.UTF_8),
                List.of(List.of(
                        "id".getBytes(StandardCharsets.UTF_8),
                        key,
                        "extra_attributes".getBytes(StandardCharsets.UTF_8),
                        List.of(
                                "dist".getBytes(StandardCharsets.UTF_8),
                                "0.05".getBytes(StandardCharsets.UTF_8),
                                "payload".getBytes(StandardCharsets.UTF_8),
                                payload))));
        SearchReply.Result modern = SearchReply.parse(dialect2);
        assertThat(modern).isNotNull();
        assertThat(modern.distance()).isEqualTo(0.05d);
        assertThat(modern.key()).isEqualTo(key);
        assertThat(modern.payload()).isEqualTo(payload);
    }

    private static float[] unit(double radians) {
        return new float[] {(float) Math.cos(radians), (float) Math.sin(radians)};
    }

    private static ChatCompletionRequest request(String content, Double temperature, String user) {
        return new ChatCompletionRequest(
                "fast",
                List.of(message("user", content)),
                temperature,
                null,
                null,
                null,
                null,
                null,
                16,
                null,
                null,
                null,
                user,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static ChatCompletionRequest withFormat(ChatCompletionRequest request, ObjectNode format) {
        return new ChatCompletionRequest(
                request.model(),
                request.messages(),
                request.temperature(),
                request.topP(),
                request.n(),
                request.stream(),
                request.streamOptions(),
                request.stop(),
                request.maxTokens(),
                request.maxCompletionTokens(),
                request.presencePenalty(),
                request.frequencyPenalty(),
                request.user(),
                format,
                request.seed(),
                request.tools(),
                request.toolChoice(),
                request.parallelToolCalls(),
                request.logprobs(),
                request.topLogprobs());
    }

    private static ChatCompletionRequest.ChatMessage message(String role, String content) {
        return new ChatCompletionRequest.ChatMessage(role, TextNode.valueOf(content), null, null, null);
    }

    private static com.fasterxml.jackson.databind.JsonNode textParts(String first, String second) {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        return factory.arrayNode()
                .add(factory.objectNode().put("type", "text").put("text", first))
                .add(factory.objectNode().put("type", "text").put("text", second));
    }
}
