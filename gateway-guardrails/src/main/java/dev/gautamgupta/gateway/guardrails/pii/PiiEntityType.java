package dev.gautamgupta.gateway.guardrails.pii;

/**
 * Entity types the built-in detectors emit. {@code metricType} is the bounded
 * {@code gateway.pii.redactions} tag. {@code token} is the placeholder prefix.
 */
public enum PiiEntityType {
    CREDIT_CARD("credit_card", "CREDIT_CARD", 0),
    AADHAAR("aadhaar", "AADHAAR", 1),
    IBAN("iban", "IBAN", 2),
    PAN("pan", "PAN", 3),
    IPV6("ipv6", "IPV6", 4),
    IPV4("ipv4", "IPV4", 5),
    PHONE("phone", "PHONE", 6),
    EMAIL("email", "EMAIL", 7);

    private final String metricType;
    private final String token;
    private final int priority;

    PiiEntityType(String metricType, String token, int priority) {
        this.metricType = metricType;
        this.token = token;
        this.priority = priority;
    }

    public String metricType() {
        return metricType;
    }

    public String token() {
        return token;
    }

    /** Lower wins when two spans have the same length. */
    public int priority() {
        return priority;
    }
}
