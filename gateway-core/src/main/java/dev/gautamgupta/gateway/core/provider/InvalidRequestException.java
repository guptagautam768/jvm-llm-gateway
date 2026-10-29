package dev.gautamgupta.gateway.core.provider;

/** Client request that fails validation before any provider is called. */
public final class InvalidRequestException extends RuntimeException {

    private final String param;

    public InvalidRequestException(String message, String param) {
        super(message);
        this.param = param;
    }

    public String param() {
        return param;
    }
}
