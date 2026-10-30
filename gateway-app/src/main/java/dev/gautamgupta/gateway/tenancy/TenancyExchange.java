package dev.gautamgupta.gateway.tenancy;

/** Request attributes shared by the auth filter and the chat handler. */
public final class TenancyExchange {

    public static final String PRINCIPAL = "gateway.tenancy.principal";
    public static final String REQUEST_RATE = "gateway.tenancy.requestRate";
    public static final String ESTIMATE = "gateway.tenancy.estimate";
    public static final String BUDGET_KEY = "gateway.tenancy.budgetKey";
    public static final String STARTED = "gateway.tenancy.started";
    public static final String MISSING_PRICES = "gateway.tenancy.missingPrices";

    private TenancyExchange() {
    }
}
