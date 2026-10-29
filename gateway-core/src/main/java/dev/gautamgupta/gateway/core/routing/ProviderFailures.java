package dev.gautamgupta.gateway.core.routing;

import dev.gautamgupta.gateway.core.provider.InvalidRequestException;
import dev.gautamgupta.gateway.core.provider.ProviderException;
import java.util.concurrent.TimeoutException;

/**
 * Which provider failures are retried, which move to the next target, and which
 * count toward the circuit breaker (ADR-0005).
 */
public final class ProviderFailures {

    private ProviderFailures() {
    }

    public static ProviderException find(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof ProviderException providerException) {
                return providerException;
            }
            cursor = cursor.getCause();
        }
        return null;
    }

    public static boolean isTimeout(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof TimeoutException) {
                return true;
            }
            if (cursor instanceof ProviderException providerException
                    && providerException.kind() == ProviderException.Kind.TIMEOUT) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    /** Bounded retry. 4xx other than 429 are not retried (FR-021). */
    public static boolean retryable(Throwable error) {
        if (error instanceof InvalidRequestException) {
            return false;
        }
        ProviderException providerException = find(error);
        if (providerException == null) {
            return isTimeout(error);
        }
        return switch (providerException.kind()) {
            case TIMEOUT, UNAVAILABLE -> true;
            case UPSTREAM -> retryableStatus(providerException.status());
            default -> false;
        };
    }

    /**
     * Move to the next target. 4xx other than 429 stop the chain and are returned
     * to the client. A missing key skips to the next target.
     */
    public static boolean fallbackEligible(Throwable error) {
        if (error instanceof InvalidRequestException) {
            return false;
        }
        ProviderException providerException = find(error);
        if (providerException == null) {
            return isTimeout(error);
        }
        return switch (providerException.kind()) {
            case TIMEOUT, UNAVAILABLE, CIRCUIT_OPEN, NOT_CONFIGURED -> true;
            case UPSTREAM -> retryableStatus(providerException.status());
            case MALFORMED -> true;
            default -> false;
        };
    }

    /**
     * 5xx, 429, timeouts, and connection failures open the breaker.
     * A 4xx means the provider answered, so it is not a failure.
     */
    public static boolean countsTowardBreaker(Throwable error) {
        if (error instanceof InvalidRequestException) {
            return false;
        }
        ProviderException providerException = find(error);
        if (providerException == null) {
            return true;
        }
        return switch (providerException.kind()) {
            case TIMEOUT, UNAVAILABLE, MALFORMED -> true;
            case UPSTREAM -> retryableStatus(providerException.status());
            default -> false;
        };
    }

    public static boolean retryableStatus(int status) {
        return status == 408 || status == 429 || status == 529 || status >= 500;
    }
}
