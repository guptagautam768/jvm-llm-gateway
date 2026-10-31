package dev.gautamgupta.gateway.tenancy.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** USD per 1K tokens. Cost is returned in micro-dollars (1 USD = 1_000_000). */
public record ModelPrice(BigDecimal promptPer1kUsd, BigDecimal completionPer1kUsd) {

    public static final ModelPrice ZERO = new ModelPrice(BigDecimal.ZERO, BigDecimal.ZERO);

    public ModelPrice {
        if (promptPer1kUsd == null || completionPer1kUsd == null) {
            throw new IllegalArgumentException("price is required");
        }
        if (promptPer1kUsd.signum() < 0 || completionPer1kUsd.signum() < 0) {
            throw new IllegalArgumentException("price cannot be negative");
        }
    }

    public long micros(int promptTokens, int completionTokens) {
        BigDecimal usd = promptPer1kUsd.multiply(BigDecimal.valueOf(Math.max(0, promptTokens)))
                .add(completionPer1kUsd.multiply(BigDecimal.valueOf(Math.max(0, completionTokens))))
                .divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP);
        return usd.multiply(BigDecimal.valueOf(1_000_000)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
