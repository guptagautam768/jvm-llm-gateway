package dev.gautamgupta.gateway.guardrails.pii;

/** A half-open match {@code [start, end)} inside one string. The matched text is not stored. */
public record PiiSpan(int start, int end, PiiEntityType type) {

    public PiiSpan {
        if (type == null) {
            throw new IllegalArgumentException("entity type is required");
        }
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("span bounds are invalid");
        }
    }

    public int length() {
        return end - start;
    }

    public boolean overlaps(PiiSpan other) {
        return start < other.end && other.start < end;
    }
}
