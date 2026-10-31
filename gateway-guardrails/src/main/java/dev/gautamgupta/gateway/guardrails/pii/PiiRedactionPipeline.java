package dev.gautamgupta.gateway.guardrails.pii;

import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.guardrails.PromptRedactor;
import dev.gautamgupta.gateway.guardrails.pii.detectors.AadhaarDetector;
import dev.gautamgupta.gateway.guardrails.pii.detectors.CreditCardDetector;
import dev.gautamgupta.gateway.guardrails.pii.detectors.EmailDetector;
import dev.gautamgupta.gateway.guardrails.pii.detectors.IbanDetector;
import dev.gautamgupta.gateway.guardrails.pii.detectors.Ipv4Detector;
import dev.gautamgupta.gateway.guardrails.pii.detectors.Ipv6Detector;
import dev.gautamgupta.gateway.guardrails.pii.detectors.PanDetector;
import dev.gautamgupta.gateway.guardrails.pii.detectors.PhoneDetector;
import java.util.ArrayList;
import java.util.List;

/**
 * Redact-and-forward pipeline (ADR-0009). Placeholders are stable inside one request.
 * The mapping is not stored. This class does not log the prompt.
 */
public final class PiiRedactionPipeline implements PromptRedactor {

    private final GuardrailSettings settings;
    private final List<PiiDetector> detectors;

    public PiiRedactionPipeline(GuardrailSettings settings, List<PiiDetector> detectors) {
        this.settings = settings == null ? GuardrailSettings.defaults() : settings;
        this.detectors = detectors == null ? List.of() : List.copyOf(detectors);
    }

    public static PiiRedactionPipeline create(GuardrailSettings settings) {
        GuardrailSettings actual = settings == null ? GuardrailSettings.defaults() : settings;
        List<PiiDetector> detectors = new ArrayList<>();
        if (actual.email()) {
            detectors.add(new EmailDetector());
        }
        if (actual.phone()) {
            detectors.add(new PhoneDetector());
        }
        if (actual.creditCard()) {
            detectors.add(new CreditCardDetector());
        }
        if (actual.ipv4()) {
            detectors.add(new Ipv4Detector());
        }
        if (actual.ipv6()) {
            detectors.add(new Ipv6Detector());
        }
        if (actual.iban()) {
            detectors.add(new IbanDetector());
        }
        if (actual.pan()) {
            detectors.add(new PanDetector());
        }
        if (actual.aadhaar()) {
            detectors.add(new AadhaarDetector());
        }
        return new PiiRedactionPipeline(actual, detectors);
    }

    @Override
    public ChatCompletionRequest redact(ChatCompletionRequest request) {
        RedactionResult result = apply(request, null);
        if (result.rejected()) {
            throw new PiiDetectedException(result.counts());
        }
        return result.request();
    }

    @Override
    public RedactionResult apply(ChatCompletionRequest request, String tenantId) {
        if (request == null || settings.modeFor(tenantId) == PiiMode.OFF) {
            return RedactionResult.unchanged(request);
        }
        RedactionSession session = new RedactionSession(detectors);
        List<ChatCompletionRequest.ChatMessage> messages = MessageSurfaces.rewriteAll(request.messages(), session);
        if (session.counts().isEmpty()) {
            return RedactionResult.unchanged(request);
        }
        ChatCompletionRequest redacted = messages == request.messages() ? request : request.withMessages(messages);
        boolean rejected = settings.modeFor(tenantId) == PiiMode.REJECT;
        return new RedactionResult(redacted, session.counts(), rejected);
    }
}
