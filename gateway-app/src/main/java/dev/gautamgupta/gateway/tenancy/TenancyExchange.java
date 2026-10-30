package dev.gautamgupta.gateway.tenancy;

/** Request attributes shared by the auth filter and the chat handler. */
public final class TenancyExchange {

    public static final String PRINCIPAL = "gateway.tenancy.principal";
    public static final String REQUEST_RATE = "gateway.tenancy.requestRate";
    public static final String ESTIMATE = "gateway.tenancy.estimate";
    public static final String BUDGET_KEY = "gateway.tenancy.budgetKey";
    public static final String STARTED = "gateway.tenancy.started";
    public static final String MISSING_PRICES = "gateway.tenancy.missingPrices";
    /** Ledger value: {@code none}, {@code exact}, or {@code semantic}. */
    public static final String CACHE = "gateway.tenancy.cache";
    /** When true, reconcile does not add provider spend. The pre-flight check already ran. */
    public static final String CACHE_HIT = "gateway.tenancy.cacheHit";

    private TenancyExchange() {
    }
}
