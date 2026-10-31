package dev.gautamgupta.gateway.guardrails.pii;

import java.util.List;

/**
 * One detector in the ADR-0009 pipeline. Implementations return spans only.
 * They must not log the input.
 */
public interface PiiDetector {

    PiiEntityType type();

    List<PiiSpan> detect(String text);
}
