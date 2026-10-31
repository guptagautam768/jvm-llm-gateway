package dev.gautamgupta.gateway.core.routing;

import dev.gautamgupta.gateway.core.provider.ProviderException;

/** Bounded {@code kind} tag for {@code gateway.provider.errors}. */
public final class ProviderErrorKinds {

    private ProviderErrorKinds() {
    }

    public static String of(Throwable error) {
        ProviderException providerException = ProviderFailures.find(error);
        if (providerException == null) {
            return ProviderFailures.isTimeout(error) ? "timeout" : "other";
        }
        return switch (providerException.kind()) {
            case TIMEOUT -> "timeout";
            case UNAVAILABLE -> "unavailable";
            case CIRCUIT_OPEN -> "circuit_open";
            case MALFORMED -> "malformed";
            case NOT_CONFIGURED -> "not_configured";
            case UNSUPPORTED -> "unsupported";
            case UPSTREAM -> upstream(providerException.status());
        };
    }

    private static String upstream(int status) {
        if (status == 429) {
            return "upstream_429";
        }
        if (status >= 500 && status <= 599) {
            return "upstream_5xx";
        }
        if (status >= 400 && status <= 499) {
            return "upstream_4xx";
        }
        return "upstream";
    }
}
