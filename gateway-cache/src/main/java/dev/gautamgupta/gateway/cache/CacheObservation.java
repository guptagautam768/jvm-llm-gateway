package dev.gautamgupta.gateway.cache;

/** One sample of {@code gateway.cache}: {@code result} is hit or miss, {@code type} is exact or semantic. */
public record CacheObservation(String result, String type) {
}
