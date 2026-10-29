package dev.gautamgupta.gateway.core.provider;

/**
 * Failure talking to a provider. The HTTP layer maps {@link Kind} to a status
 * when the provider did not return one itself.
 */
public final class ProviderException extends RuntimeException {

    public enum Kind {
        NOT_CONFIGURED,
        UNAVAILABLE,
        TIMEOUT,
        UPSTREAM,
        UNSUPPORTED,
        MALFORMED,
        CIRCUIT_OPEN
    }

    private final String providerId;
    private final Kind kind;
    private final int status;
    private final String responseBody;
    private final String retryAfter;

    private ProviderException(
            String providerId,
            Kind kind,
            int status,
            String responseBody,
            String retryAfter,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.providerId = providerId;
        this.kind = kind;
        this.status = status;
        this.responseBody = responseBody;
        this.retryAfter = retryAfter;
    }

    public static ProviderException notConfigured(String providerId) {
        return new ProviderException(
                providerId,
                Kind.NOT_CONFIGURED,
                503,
                null,
                null,
                providerId + " is not configured",
                null);
    }

    public static ProviderException unavailable(String providerId, Throwable cause) {
        return new ProviderException(
                providerId,
                Kind.UNAVAILABLE,
                502,
                null,
                null,
                providerId + " could not be reached",
                cause);
    }

    public static ProviderException timeout(String providerId, Throwable cause) {
        return new ProviderException(
                providerId,
                Kind.TIMEOUT,
                504,
                null,
                null,
                providerId + " timed out",
                cause);
    }

    public static ProviderException upstream(String providerId, int status, String responseBody, String retryAfter) {
        return new ProviderException(
                providerId,
                Kind.UPSTREAM,
                status,
                responseBody,
                retryAfter,
                providerId + " returned HTTP " + status,
                null);
    }

    public static ProviderException unsupported(String providerId, String operation) {
        return new ProviderException(
                providerId,
                Kind.UNSUPPORTED,
                501,
                null,
                null,
                providerId + " does not implement " + operation,
                null);
    }

    public static ProviderException malformed(String providerId, String detail, Throwable cause) {
        return new ProviderException(
                providerId,
                Kind.MALFORMED,
                502,
                null,
                null,
                providerId + " returned a malformed response: " + detail,
                cause);
    }

    public static ProviderException circuitOpen(String providerId) {
        return withGatewayBody(
                providerId,
                Kind.CIRCUIT_OPEN,
                502,
                "circuit_open",
                "The circuit breaker is open for " + providerId + ".");
    }

    /**
     * Chain exhausted on a provider failure that ADR-0005 maps to 502.
     * The body is an OpenAI-shaped gateway error, not the upstream payload.
     */
    public static ProviderException exhausted(String providerId) {
        return withGatewayBody(
                providerId,
                Kind.UPSTREAM,
                502,
                "upstream_unavailable",
                "Upstream providers failed after retries and fallbacks.");
    }

    private static ProviderException withGatewayBody(
            String providerId,
            Kind kind,
            int status,
            String code,
            String message
    ) {
        return new ProviderException(
                providerId,
                kind,
                status,
                errorBody(message, code),
                null,
                message,
                null);
    }

    private static String errorBody(String message, String code) {
        return "{\"error\":{\"message\":\"" + escape(message) + "\",\"type\":\"server_error\",\"code\":\""
                + escape(code) + "\"}}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public String providerId() {
        return providerId;
    }

    public Kind kind() {
        return kind;
    }

    public int status() {
        return status;
    }

    public String responseBody() {
        return responseBody;
    }

    public String retryAfter() {
        return retryAfter;
    }
}
