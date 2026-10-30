package dev.gautamgupta.gateway.tenancy.budget;

public record BudgetCheck(boolean allowed, Reason reason, long tokensUsed, long costMicrosUsed) {

    public enum Reason {
        OK,
        TOKEN,
        COST
    }

    public static BudgetCheck parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty budget result");
        }
        String[] parts = raw.trim().split("\\|");
        if (parts.length != 4) {
            throw new IllegalArgumentException("unexpected budget result");
        }
        boolean allowed = "1".equals(parts[0]);
        Reason reason = switch (parts[1]) {
            case "1" -> Reason.TOKEN;
            case "2" -> Reason.COST;
            default -> Reason.OK;
        };
        return new BudgetCheck(allowed, reason, Long.parseLong(parts[2]), Long.parseLong(parts[3]));
    }
}
