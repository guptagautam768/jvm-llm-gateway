package dev.gautamgupta.gateway.api.error;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** OpenAI-style error body (API-004). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(ApiError error) {

    public static ApiErrorResponse of(String message, String type, String code, String param) {
        return new ApiErrorResponse(new ApiError(message, type, code, param));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiError(String message, String type, String code, String param) {
    }
}
