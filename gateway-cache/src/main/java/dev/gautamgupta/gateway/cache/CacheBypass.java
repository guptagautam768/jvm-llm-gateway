package dev.gautamgupta.gateway.cache;

/**
 * {@code X-Cache-Bypass: true} skips lookup and write (ADR-0006, FR-032).
 * The match is case-insensitive and ignores surrounding space. Other values,
 * including {@code 1} and {@code yes}, do not bypass.
 */
public final class CacheBypass {

    public static final String HEADER = "X-Cache-Bypass";

    private CacheBypass() {
    }

    public static boolean requested(String header) {
        return header != null && "true".equalsIgnoreCase(header.trim());
    }
}
