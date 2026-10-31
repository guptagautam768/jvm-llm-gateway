package dev.gautamgupta.gateway.guardrails.pii;

/** FR-051. HTTP 413 {@code request_too_large}. The message has no message text. */
public final class RequestTooLargeException extends RuntimeException {

    private final long bytes;
    private final long limit;

    public RequestTooLargeException(long bytes, long limit) {
        super("The request exceeds the maximum message size.");
        this.bytes = bytes;
        this.limit = limit;
    }

    public long bytes() {
        return bytes;
    }

    public long limit() {
        return limit;
    }

    public String code() {
        return "request_too_large";
    }
}
