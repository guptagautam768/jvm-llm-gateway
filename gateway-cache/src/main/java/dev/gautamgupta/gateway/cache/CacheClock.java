package dev.gautamgupta.gateway.cache;

/** Millis used as the LRU score. Production uses {@link System#currentTimeMillis()}. */
@FunctionalInterface
public interface CacheClock {

    long nowMillis();
}
