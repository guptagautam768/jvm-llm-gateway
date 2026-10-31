package dev.gautamgupta.gateway.cache;

/**
 * Defaults chosen with the implementation, as ADR-0006 left the number open.
 *
 * <p>{@link #DEFAULT} is the cosine of about 23 degrees. On unit vectors a 15 degree
 * near-duplicate is about 0.966 and hits; a 30 degree related prompt is about 0.866
 * and misses. CI stubs vectors on either side of this line. It is a correctness
 * default, not a measured hit rate against a live embedding model, and routes can
 * override it. {@link #MAX_TEMPERATURE} is the top of the low-temperature band.
 * OpenAI's omitted temperature is 1, so those requests stay out of the semantic cache.
 */
public final class SemanticThreshold {

    public static final double DEFAULT = 0.92d;

    public static final double MAX_TEMPERATURE = 0.2d;

    private SemanticThreshold() {
    }
}
