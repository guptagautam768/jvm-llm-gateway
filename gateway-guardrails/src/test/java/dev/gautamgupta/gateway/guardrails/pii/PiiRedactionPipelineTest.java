package dev.gautamgupta.gateway.guardrails.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest.ChatMessage;
import dev.gautamgupta.gateway.guardrails.pii.checksum.Verhoeff;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PiiRedactionPipelineTest {

    private final PiiRedactionPipeline pipeline = PiiRedactionPipeline.create(GuardrailSettings.defaults());

    @Test
    void replacesEachTypeAndReusesThePlaceholderForTheSameValue() {
        String aadhaar = "23456789012" + Verhoeff.checkDigit("23456789012");
        ChatCompletionRequest request = request(
                "Email ada@example.com and ada@example.com, call +91 9876543210, card 4111111111111111, "
                        + "host 192.168.0.1, v6 2001:db8::1, iban DE89370400440532013000, pan ABCPD1234F, uid "
                        + aadhaar);

        RedactionResult result = pipeline.apply(request, "acme");

        assertThat(result.rejected()).isFalse();
        String text = result.request().messages().get(0).content().asText();
        assertThat(text)
                .contains("[EMAIL_1]")
                .contains("[PHONE_1]")
                .contains("[CREDIT_CARD_1]")
                .contains("[IPV4_1]")
                .contains("[IPV6_1]")
                .contains("[IBAN_1]")
                .contains("[PAN_1]")
                .contains("[AADHAAR_1]")
                .doesNotContain("ada@example.com")
                .doesNotContain("9876543210")
                .doesNotContain("4111111111111111")
                .doesNotContain(aadhaar);
        assertThat(text).contains("[EMAIL_1] and [EMAIL_1]");
        assertThat(result.counts())
                .containsEntry("email", 2)
                .containsEntry("phone", 1)
                .containsEntry("credit_card", 1)
                .containsEntry("ipv4", 1)
                .containsEntry("ipv6", 1)
                .containsEntry("iban", 1)
                .containsEntry("pan", 1)
                .containsEntry("aadhaar", 1);
    }

    @Test
    void numbersPlaceholdersInFirstSeenOrder() {
        RedactionResult result = pipeline.apply(request("a@example.com then b@example.com"), null);
        assertThat(result.request().messages().get(0).content().asText())
                .isEqualTo("[EMAIL_1] then [EMAIL_2]");
    }

    @Test
    void creditCardBeatsAShorterOverlappingPattern() {
        RedactionResult result = pipeline.apply(request("pay 4111111111111111 now"), "acme");
        assertThat(result.request().messages().get(0).content().asText()).isEqualTo("pay [CREDIT_CARD_1] now");
        assertThat(result.counts()).containsOnlyKeys("credit_card");
    }

    @Test
    void redactsTextPartsNamesAndToolArguments() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChatMessage message = new ChatMessage(
                "user",
                mapper.readTree("[{\"type\":\"text\",\"text\":\"mail ada@example.com\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.com/a.png\"}}]"),
                "ada@example.com",
                mapper.readTree("[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"send\",\"arguments\":\"{\\\"to\\\":\\\"ada@example.com\\\"}\"}}]"),
                "call_1");
        ChatCompletionRequest request = new ChatCompletionRequest(
                "fast", List.of(message), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        RedactionResult result = pipeline.apply(request, "acme");
        ChatMessage redacted = result.request().messages().get(0);

        assertThat(redacted.content().get(0).get("text").asText()).isEqualTo("mail [EMAIL_1]");
        assertThat(redacted.content().get(1).get("image_url").get("url").asText()).isEqualTo("https://example.com/a.png");
        assertThat(redacted.name()).isEqualTo("[EMAIL_1]");
        assertThat(redacted.toolCalls().get(0).get("function").get("arguments").asText()).contains("[EMAIL_1]");
        assertThat(redacted.toolCalls().get(0).get("function").get("name").asText()).isEqualTo("send");
        assertThat(result.counts()).containsEntry("email", 3);
    }

    @Test
    void offModeLeavesThePromptAlone() {
        PiiRedactionPipeline off = PiiRedactionPipeline.create(new GuardrailSettings(
                PiiMode.OFF, Map.of(), true, true, true, true, true, true, true, true));
        ChatCompletionRequest request = request("ada@example.com");
        RedactionResult result = off.apply(request, "acme");
        assertThat(result.request()).isSameAs(request);
        assertThat(result.counts()).isEmpty();
        assertThat(result.rejected()).isFalse();
    }

    @Test
    void rejectModeFlagsCountsAndDoesNotRequireTheCallerToForward() {
        PiiRedactionPipeline reject = PiiRedactionPipeline.create(new GuardrailSettings(
                PiiMode.REJECT, Map.of(), true, true, true, true, true, true, true, true));
        RedactionResult result = reject.apply(request("ada@example.com"), "acme");
        assertThat(result.rejected()).isTrue();
        assertThat(result.counts()).containsEntry("email", 1);
        assertThatThrownBy(() -> reject.redact(request("ada@example.com")))
                .isInstanceOf(PiiDetectedException.class)
                .hasMessageNotContaining("ada@example.com");
    }

    @Test
    void aTenantOverrideWinsOverTheDeploymentMode() {
        PiiRedactionPipeline mixed = PiiRedactionPipeline.create(new GuardrailSettings(
                PiiMode.REDACT, Map.of("secret", PiiMode.REJECT, "open", PiiMode.OFF),
                true, true, true, true, true, true, true, true));
        assertThat(mixed.apply(request("ada@example.com"), "secret").rejected()).isTrue();
        assertThat(mixed.apply(request("ada@example.com"), "open").request().messages().get(0).content().asText())
                .isEqualTo("ada@example.com");
        assertThat(mixed.apply(request("ada@example.com"), "other").request().messages().get(0).content().asText())
                .isEqualTo("[EMAIL_1]");
    }

    @Test
    void aDisabledDetectorIsSkipped() {
        GuardrailSettings settings = new GuardrailSettings(
                PiiMode.REDACT, Map.of(), true, false, false, false, false, false, false, false);
        PiiRedactionPipeline emailOnly = PiiRedactionPipeline.create(settings);
        RedactionResult result = emailOnly.apply(request("ada@example.com and 9876543210"), null);
        assertThat(result.request().messages().get(0).content().asText())
                .isEqualTo("[EMAIL_1] and 9876543210");
    }

    @Test
    void cleanTextIsUnchanged() {
        ChatCompletionRequest request = request("Say hello");
        RedactionResult result = pipeline.apply(request, null);
        assertThat(result.request()).isSameAs(request);
        assertThat(result.counts()).isEmpty();
    }

    private static ChatCompletionRequest request(String content) {
        return new ChatCompletionRequest(
                "fast",
                List.of(new ChatMessage("user", TextNode.valueOf(content), null, null, null)),
                0.0, null, null, null, null, null, 16, null, null, null, null, null, null, null, null, null, null, null);
    }
}
