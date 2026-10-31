package dev.gautamgupta.gateway.web;

import dev.gautamgupta.gateway.api.embed.EmbeddingRequest;
import dev.gautamgupta.gateway.core.embed.EmbeddingInputs;
import dev.gautamgupta.gateway.guardrails.PromptRedactor;
import dev.gautamgupta.gateway.guardrails.RequestSizeLimit;
import dev.gautamgupta.gateway.guardrails.pii.PiiDetectedException;
import dev.gautamgupta.gateway.guardrails.pii.RedactionCounts;
import dev.gautamgupta.gateway.guardrails.pii.TextRedaction;
import dev.gautamgupta.gateway.metrics.GatewayMetrics;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-0009 order for embeddings input: size limit, then redaction. The provider
 * sees the redacted text. Embeddings responses are not cached (ADR-0006, ADR-0015).
 */
public final class EmbeddingGuard {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingGuard.class);

    private final PromptRedactor redactor;
    private final RequestSizeLimit sizeLimit;
    private final GatewayMetrics metrics;

    public EmbeddingGuard(PromptRedactor redactor, RequestSizeLimit sizeLimit, GatewayMetrics metrics) {
        this.redactor = redactor;
        this.sizeLimit = sizeLimit;
        this.metrics = metrics;
    }

    public EmbeddingRequest prepare(String tenantId, EmbeddingRequest request, List<String> texts) {
        sizeLimit.checkBytes(EmbeddingInputs.utf8Bytes(texts));
        TextRedaction redaction = redactor.applyTexts(texts, tenantId);
        if (metrics != null) {
            metrics.recordPiiRedactions(redaction.counts(), tenantId);
        }
        if (!redaction.counts().isEmpty()) {
            log.info(
                    "pii redactions tenant={} {}",
                    tenantId == null || tenantId.isBlank() ? "none" : tenantId,
                    RedactionCounts.format(redaction.counts()));
        }
        if (redaction.rejected()) {
            throw new PiiDetectedException(redaction.counts());
        }
        boolean array = request.input() != null && request.input().isArray();
        return request.withInput(EmbeddingInputs.node(null, redaction.texts(), array));
    }
}
