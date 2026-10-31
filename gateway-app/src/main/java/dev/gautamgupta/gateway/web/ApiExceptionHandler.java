package dev.gautamgupta.gateway.web;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gautamgupta.gateway.api.error.ApiErrorResponse;
import dev.gautamgupta.gateway.core.provider.InvalidRequestException;
import dev.gautamgupta.gateway.core.provider.ProviderException;
import dev.gautamgupta.gateway.guardrails.pii.PiiDetectedException;
import dev.gautamgupta.gateway.guardrails.pii.RedactionCounts;
import dev.gautamgupta.gateway.guardrails.pii.RequestTooLargeException;
import dev.gautamgupta.gateway.tenancy.RateLimitHeaders;
import dev.gautamgupta.gateway.tenancy.error.TenancyException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public ApiExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(TenancyException.class)
    public ResponseEntity<ApiErrorResponse> tenancy(TenancyException ex) {
        if (ex.kind() == TenancyException.Kind.DEPENDENCY) {
            log.error("tenancy dependency failed ({})", ex.getCause() == null ? "unknown" : ex.getCause().getClass().getSimpleName());
        } else {
            log.warn("rejected request code={}", ex.code());
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.httpStatus()).contentType(MediaType.APPLICATION_JSON);
        if (ex.retryAfterSeconds() != null && ex.retryAfterSeconds() > 0) {
            builder.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
        }
        if (ex.rateLimit() != null) {
            builder.header(RateLimitHeaders.LIMIT, Long.toString(ex.rateLimit().limit()));
            builder.header(RateLimitHeaders.REMAINING, Long.toString(ex.rateLimit().remaining()));
            builder.header(RateLimitHeaders.RESET, Long.toString(ex.rateLimit().resetEpochSeconds()));
        }
        return builder.body(ApiErrorResponse.of(ex.getMessage(), ex.type(), ex.code(), null));
    }

    @ExceptionHandler(PiiDetectedException.class)
    public ResponseEntity<ApiErrorResponse> pii(PiiDetectedException ex) {
        log.warn("rejected request code=pii_detected {}", RedactionCounts.format(ex.counts()));
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.of(
                        "The request was rejected because it contains personal data.",
                        "invalid_request_error",
                        ex.code(),
                        null));
    }

    @ExceptionHandler(RequestTooLargeException.class)
    public ResponseEntity<ApiErrorResponse> tooLarge(RequestTooLargeException ex) {
        log.warn("rejected request code=request_too_large bytes={} limit={}", ex.bytes(), ex.limit());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.of(
                        "The request exceeds the maximum message size.",
                        "invalid_request_error",
                        ex.code(),
                        null));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiErrorResponse> invalid(InvalidRequestException ex) {
        log.warn("rejected request code=invalid_request param={}", ex.param());
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.of(ex.getMessage(), "invalid_request_error", "invalid_request", ex.param()));
    }

    @ExceptionHandler(ProviderException.class)
    public ResponseEntity<?> provider(ProviderException ex) {
        log.warn("provider failure provider={} kind={} status={}", ex.providerId(), ex.kind(), ex.status());
        ResponseEntity<?> passthrough = passthroughUpstream(ex);
        if (passthrough != null) {
            return passthrough;
        }
        int status = normalize(ex);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.of(publicMessage(ex), typeOf(ex), codeOf(ex), null));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> status(ResponseStatusException ex) {
        HttpStatusCode code = ex.getStatusCode();
        if (code.value() == HttpStatus.NOT_FOUND.value()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ApiErrorResponse.of("Not found.", "invalid_request_error", "not_found", null));
        }
        log.warn("request failed status={} ({})", code.value(), ex.getClass().getSimpleName());
        return ResponseEntity.status(code)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.of("Request failed.", "server_error", "request_failed", null));
    }

    @ExceptionHandler({ServerWebInputException.class, DecodingException.class})
    public ResponseEntity<ApiErrorResponse> malformed(Exception ex) {
        log.warn("malformed request ({})", ex.getClass().getSimpleName());
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.of(
                        "Malformed JSON request body.",
                        "invalid_request_error",
                        "invalid_json",
                        null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> fallback(Exception ex) {
        log.error("unhandled failure ({})", ex.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.of(
                        "The gateway failed before completing the request.",
                        "server_error",
                        "internal_error",
                        null));
    }

    private ResponseEntity<?> passthroughUpstream(ProviderException ex) {
        if (ex.kind() != ProviderException.Kind.UPSTREAM || ex.responseBody() == null || ex.responseBody().isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(ex.responseBody());
            if (!node.isObject() || !node.has("error")) {
                return null;
            }
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(normalize(ex)).contentType(MediaType.APPLICATION_JSON);
            if (ex.retryAfter() != null && !ex.retryAfter().isBlank()) {
                builder.header(HttpHeaders.RETRY_AFTER, ex.retryAfter());
            }
            return builder.body(node);
        } catch (JacksonException ignored) {
            return null;
        }
    }

    private static int normalize(ProviderException ex) {
        int status = ex.status();
        if (status >= 400 && status <= 599) {
            return status;
        }
        return switch (ex.kind()) {
            case NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE.value();
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT.value();
            case UNSUPPORTED -> HttpStatus.NOT_IMPLEMENTED.value();
            default -> HttpStatus.BAD_GATEWAY.value();
        };
    }

    private static String publicMessage(ProviderException ex) {
        return switch (ex.kind()) {
            case NOT_CONFIGURED -> "The " + ex.providerId() + " provider is not configured. Set "
                    + apiKeyVariable(ex.providerId()) + ".";
            case TIMEOUT -> "The provider timed out.";
            case UNAVAILABLE -> "The provider could not be reached.";
            case MALFORMED -> "The provider returned a response the gateway could not read.";
            case UNSUPPORTED -> "This operation is not implemented for the selected provider.";
            case CIRCUIT_OPEN -> "The circuit breaker is open.";
            case UPSTREAM -> "The provider returned an error.";
        };
    }

    private static String codeOf(ProviderException ex) {
        return switch (ex.kind()) {
            case NOT_CONFIGURED -> "provider_not_configured";
            case TIMEOUT -> "provider_timeout";
            case UNAVAILABLE -> "provider_unavailable";
            case MALFORMED -> "provider_malformed_response";
            case UNSUPPORTED -> "not_implemented";
            case CIRCUIT_OPEN -> "circuit_open";
            case UPSTREAM -> "upstream_error";
        };
    }

    private static String apiKeyVariable(String providerId) {
        return switch (providerId) {
            case "openai" -> "OPENAI_API_KEY";
            case "anthropic" -> "ANTHROPIC_API_KEY";
            default -> providerId.toUpperCase(Locale.ROOT) + "_API_KEY";
        };
    }

    private static String typeOf(ProviderException ex) {
        if (ex.kind() == ProviderException.Kind.UNSUPPORTED) {
            return "invalid_request_error";
        }
        return "server_error";
    }
}
