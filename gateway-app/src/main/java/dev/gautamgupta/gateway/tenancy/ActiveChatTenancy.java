package dev.gautamgupta.gateway.tenancy;

import dev.gautamgupta.gateway.api.Usage;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.core.routing.ModelRouter;
import dev.gautamgupta.gateway.core.routing.ResolvedRoute;
import dev.gautamgupta.gateway.metrics.GatewayMetrics;
import dev.gautamgupta.gateway.persistence.ledger.UsageLedger;
import dev.gautamgupta.gateway.persistence.ledger.UsageLedgerEntry;
import dev.gautamgupta.gateway.tenancy.apikey.ApiKeyRecord;
import dev.gautamgupta.gateway.tenancy.apikey.TenantLimits;
import dev.gautamgupta.gateway.tenancy.budget.BudgetCheck;
import dev.gautamgupta.gateway.tenancy.budget.BudgetCounter;
import dev.gautamgupta.gateway.tenancy.budget.BudgetWindow;
import dev.gautamgupta.gateway.tenancy.budget.ModelPrice;
import dev.gautamgupta.gateway.tenancy.budget.PriceCatalog;
import dev.gautamgupta.gateway.tenancy.budget.TokenEstimator;
import dev.gautamgupta.gateway.tenancy.budget.UsageEstimate;
import dev.gautamgupta.gateway.tenancy.error.TenancyException;
import dev.gautamgupta.gateway.tenancy.limit.RateLimitDecision;
import dev.gautamgupta.gateway.tenancy.limit.RequestLimiter;
import dev.gautamgupta.gateway.web.RequestIdWebFilter;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Token and dollar budgets, plus the async ledger row. The pre-flight check does not
 * increment Redis. Reconcile adds provider usage when the response includes it, and the
 * pre-flight estimate when it does not (streaming responses that omit usage).
 */
public final class ActiveChatTenancy implements ChatTenancy {

    private static final Logger log = LoggerFactory.getLogger(ActiveChatTenancy.class);
    private static final String ROUTE = "chat.completions";

    private final RequestLimiter limiter;
    private final BudgetCounter budgets;
    private final UsageLedger ledger;
    private final ModelRouter router;
    private final TokenEstimator estimator;
    private final PriceCatalog prices;
    private final GatewayMetrics metrics;

    public ActiveChatTenancy(
            RequestLimiter limiter,
            BudgetCounter budgets,
            UsageLedger ledger,
            ModelRouter router,
            TokenEstimator estimator,
            PriceCatalog prices,
            GatewayMetrics metrics
    ) {
        this.limiter = limiter;
        this.budgets = budgets;
        this.ledger = ledger;
        this.router = router;
        this.estimator = estimator;
        this.prices = prices;
        this.metrics = metrics;
    }

    @Override
    public Mono<Void> preflight(ServerWebExchange exchange, ChatCompletionRequest request) {
        ApiKeyRecord principal = principal(exchange);
        if (principal == null) {
            return Mono.error(TenancyException.invalidApiKey());
        }
        UsageEstimate estimate = estimator.estimate(request);
        exchange.getAttributes().put(TenancyExchange.ESTIMATE, estimate);
        ResolvedRoute route = router.resolve(request.model());
        String priceModel = route.targets().get(0).model();
        Optional<ModelPrice> price = prices.find(priceModel);
        boolean enforceCost = price.isPresent() && principal.limits().hasCostBudget();
        long estimatedCost = enforceCost
                ? price.get().micros(estimate.promptTokens(), estimate.completionTokens())
                : 0L;
        if (price.isEmpty()) {
            noteMissing(exchange, priceModel, principal.tenantId());
        }
        String budgetKey = BudgetWindow.redisKey(
                principal.tenantId(),
                OffsetDateTime.now(ZoneOffset.UTC).toInstant(),
                principal.limits().budgetWindow());
        exchange.getAttributes().put(TenancyExchange.BUDGET_KEY, budgetKey);
        return tokensPerMinute(exchange, principal, estimate, request.model())
                .then(Mono.defer(() -> budgetCheck(exchange, principal, estimate, enforceCost, estimatedCost, priceModel)));
    }

    @Override
    public Mono<Void> reconcile(ServerWebExchange exchange, String servedModel, Usage usage) {
        ApiKeyRecord principal = principal(exchange);
        if (principal == null) {
            return Mono.empty();
        }
        int[] tokens = chargedTokens(exchange, usage);
        if (tokens == null) {
            return Mono.empty();
        }
        String model = priceModel(servedModel, exchange);
        long cost = prices.costMicros(model, tokens[0], tokens[1]).orElse(0L);
        String key = exchange.getAttribute(TenancyExchange.BUDGET_KEY);
        if (key == null) {
            key = BudgetWindow.redisKey(
                    principal.tenantId(),
                    OffsetDateTime.now(ZoneOffset.UTC).toInstant(),
                    principal.limits().budgetWindow());
        }
        return budgets.add(key, BudgetWindow.ttlMillis(principal.limits().budgetWindow()), tokens[0] + tokens[1], cost)
                .onErrorResume(error -> {
                    log.warn("budget reconcile failed tenant={}", principal.tenantId());
                    return Mono.empty();
                });
    }

    @Override
    public void record(
            ServerWebExchange exchange,
            String route,
            String provider,
            String model,
            Usage usage,
            int status,
            String errorCode,
            long startNanos
    ) {
        ApiKeyRecord principal = principal(exchange);
        if (principal == null || ledger == null) {
            return;
        }
        Integer prompt = usage == null ? null : usage.promptTokens();
        Integer completion = usage == null ? null : usage.completionTokens();
        if (prompt == null && completion == null) {
            UsageEstimate estimate = exchange.getAttribute(TenancyExchange.ESTIMATE);
            if (estimate != null && (status == 429 || usage == null)) {
                prompt = estimate.promptTokens();
                completion = estimate.completionTokens();
            }
        }
        String priced = priceModel(model, exchange);
        Long cost = null;
        if (prompt != null || completion != null) {
            int promptTokens = prompt == null ? 0 : prompt;
            int completionTokens = completion == null ? 0 : completion;
            Optional<Long> micros = prices.costMicros(priced, promptTokens, completionTokens);
            if (micros.isEmpty()) {
                if (priced != null) {
                    noteMissing(exchange, priced, principal.tenantId());
                }
            } else {
                cost = micros.get();
                if (status >= 200 && status < 300) {
                    metrics.recordEstimatedCost(priced, cost, principal.tenantId());
                }
            }
        }
        Long started = exchange.getAttribute(TenancyExchange.STARTED);
        long start = started == null ? startNanos : started;
        int latency = (int) Math.max(0, Math.min(Integer.MAX_VALUE, (System.nanoTime() - start) / 1_000_000L));
        String requestId = exchange.getResponse().getHeaders().getFirst(RequestIdWebFilter.HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = "unknown";
        }
        ledger.record(new UsageLedgerEntry(
                UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC),
                principal.tenantId(),
                requestId,
                route,
                blank(provider),
                blank(priced),
                prompt,
                completion,
                cost,
                "none",
                latency,
                status,
                errorCode));
    }

    @Override
    public String tenantId(ServerWebExchange exchange) {
        ApiKeyRecord principal = principal(exchange);
        return principal == null ? null : principal.tenantId();
    }

    private Mono<Void> tokensPerMinute(
            ServerWebExchange exchange,
            ApiKeyRecord principal,
            UsageEstimate estimate,
            String requestedModel
    ) {
        TenantLimits limits = principal.limits();
        if (!limits.hasTokensPerMinute()) {
            return Mono.empty();
        }
        long capacity = limits.tokensPerMinute();
        return limiter.consume("rl:" + principal.tenantId() + ":tpm", capacity, capacity / 60.0, Math.max(1, estimate.total()))
                .flatMap(decision -> {
                    if (decision.allowed()) {
                        return Mono.empty();
                    }
                    metrics.recordRejection("rate_limit_exceeded", requestedModel, principal.tenantId());
                    record(exchange, ROUTE, "none", requestedModel, null, 429, "rate_limit_exceeded", System.nanoTime());
                    return Mono.error(TenancyException.rateLimit(decision));
                });
    }

    private Mono<Void> budgetCheck(
            ServerWebExchange exchange,
            ApiKeyRecord principal,
            UsageEstimate estimate,
            boolean enforceCost,
            long estimatedCost,
            String priceModel
    ) {
        TenantLimits limits = principal.limits();
        if (!limits.hasTokenBudget() && !enforceCost) {
            return Mono.empty();
        }
        long tokenLimit = limits.hasTokenBudget() ? limits.tokenBudget() : -1L;
        long costLimit = limits.costBudgetMicros() == null ? 0L : limits.costBudgetMicros();
        String key = exchange.getAttribute(TenancyExchange.BUDGET_KEY);
        return budgets.check(key, tokenLimit, estimate.total(), enforceCost, costLimit, estimatedCost)
                .flatMap(check -> {
                    if (check.allowed()) {
                        return Mono.empty();
                    }
                    long retry = BudgetWindow.secondsUntilEnd(
                            OffsetDateTime.now(ZoneOffset.UTC).toInstant(),
                            limits.budgetWindow());
                    RateLimitDecision rate = exchange.getAttribute(TenancyExchange.REQUEST_RATE);
                    if (check.reason() == BudgetCheck.Reason.COST) {
                        metrics.recordRejection("cost_budget_exceeded", priceModel, principal.tenantId());
                        record(exchange, ROUTE, "none", priceModel, null, 429, "cost_budget_exceeded", System.nanoTime());
                        return Mono.error(TenancyException.costBudget(retry, rate));
                    }
                    metrics.recordRejection("token_budget_exceeded", priceModel, principal.tenantId());
                    record(exchange, ROUTE, "none", priceModel, null, 429, "token_budget_exceeded", System.nanoTime());
                    return Mono.error(TenancyException.tokenBudget(retry, rate));
                });
    }

    private void noteMissing(ServerWebExchange exchange, String model, String tenantId) {
        if (model == null || model.isBlank() || "none".equals(model)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Set<String> seen = (Set<String>) exchange.getAttribute(TenancyExchange.MISSING_PRICES);
        if (seen == null) {
            seen = new HashSet<>();
            exchange.getAttributes().put(TenancyExchange.MISSING_PRICES, seen);
        }
        if (seen.add(model)) {
            metrics.recordPriceMissing(model, tenantId);
        }
    }

    private static int[] chargedTokens(ServerWebExchange exchange, Usage usage) {
        if (usage != null && (usage.promptTokens() != null || usage.completionTokens() != null)) {
            int prompt = usage.promptTokens() == null ? 0 : usage.promptTokens();
            int completion = usage.completionTokens() == null ? 0 : usage.completionTokens();
            return new int[] {prompt, completion};
        }
        UsageEstimate estimate = exchange.getAttribute(TenancyExchange.ESTIMATE);
        if (estimate == null) {
            return null;
        }
        return new int[] {estimate.promptTokens(), estimate.completionTokens()};
    }

    private static String priceModel(String model, ServerWebExchange exchange) {
        if (model != null && !model.isBlank() && !"none".equals(model)) {
            return model;
        }
        return null;
    }

    private static ApiKeyRecord principal(ServerWebExchange exchange) {
        return exchange.getAttribute(TenancyExchange.PRINCIPAL);
    }

    private static String blank(String value) {
        if (value == null || value.isBlank() || "none".equals(value)) {
            return null;
        }
        return value;
    }
}
