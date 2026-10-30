package dev.gautamgupta.gateway.tenancy.budget;

import java.time.Duration;
import java.time.Instant;

/** Fixed windows aligned to the epoch. The Redis key uses the window start as its id. */
public final class BudgetWindow {

    private BudgetWindow() {
    }

    public static long startEpochSeconds(Instant now, Duration window) {
        long seconds = Math.max(1, window.getSeconds());
        long nowSeconds = now.getEpochSecond();
        return nowSeconds - Math.floorMod(nowSeconds, seconds);
    }

    public static long secondsUntilEnd(Instant now, Duration window) {
        long start = startEpochSeconds(now, window);
        long end = start + Math.max(1, window.getSeconds());
        return Math.max(1, end - now.getEpochSecond());
    }

    public static String redisKey(String tenantId, Instant now, Duration window) {
        return "budget:" + tenantId + ":" + startEpochSeconds(now, window);
    }

    public static long ttlMillis(Duration window) {
        return window.plus(Duration.ofDays(1)).toMillis();
    }
}
