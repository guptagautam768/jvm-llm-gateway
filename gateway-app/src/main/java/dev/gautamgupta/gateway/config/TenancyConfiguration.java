package dev.gautamgupta.gateway.config;

import dev.gautamgupta.gateway.core.provider.ProviderIds;
import dev.gautamgupta.gateway.core.routing.ModelRouter;
import dev.gautamgupta.gateway.metrics.GatewayMetrics;
import dev.gautamgupta.gateway.persistence.ledger.AsyncUsageLedger;
import dev.gautamgupta.gateway.persistence.ledger.LedgerMaintenanceJob;
import dev.gautamgupta.gateway.persistence.ledger.R2dbcUsageLedgerWriter;
import dev.gautamgupta.gateway.persistence.ledger.UsageLedger;
import dev.gautamgupta.gateway.persistence.ledger.UsageLedgerEvents;
import dev.gautamgupta.gateway.persistence.tenant.R2dbcApiKeyDirectory;
import dev.gautamgupta.gateway.persistence.tenant.TenantBootstrap;
import dev.gautamgupta.gateway.persistence.tenant.TenantSeed;
import dev.gautamgupta.gateway.tenancy.ActiveChatTenancy;
import dev.gautamgupta.gateway.tenancy.ChatTenancy;
import dev.gautamgupta.gateway.tenancy.NoopChatTenancy;
import dev.gautamgupta.gateway.tenancy.apikey.ApiKeyRecord;
import dev.gautamgupta.gateway.tenancy.apikey.CachingApiKeyDirectory;
import dev.gautamgupta.gateway.tenancy.apikey.TenantAuthenticator;
import dev.gautamgupta.gateway.tenancy.budget.BudgetCounter;
import dev.gautamgupta.gateway.tenancy.budget.ModelPrice;
import dev.gautamgupta.gateway.tenancy.budget.PriceCatalog;
import dev.gautamgupta.gateway.tenancy.budget.RedisBudgetCounter;
import dev.gautamgupta.gateway.tenancy.budget.TokenEstimator;
import dev.gautamgupta.gateway.tenancy.limit.RedisTokenBucket;
import dev.gautamgupta.gateway.tenancy.limit.RequestLimiter;
import dev.gautamgupta.gateway.web.ChatTenancyWebFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.r2dbc.R2dbcLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import io.micrometer.observation.ObservationRegistry;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        TenancyProperties.class,
        LedgerProperties.class,
        GatewayPriceProperties.class
})
public class TenancyConfiguration {

    @Bean
    PriceCatalog priceCatalog(GatewayPriceProperties properties) {
        Map<String, ModelPrice> prices = new LinkedHashMap<>();
        properties.getPrices().forEach((model, quote) -> prices.put(
                model,
                new ModelPrice(quote.getPromptPer1kUsd(), quote.getCompletionPer1kUsd())));
        return new PriceCatalog(prices, java.util.Set.of(ProviderIds.OLLAMA));
    }

    @Bean
    TokenEstimator tokenEstimator(TenancyProperties properties) {
        return new TokenEstimator(properties.getDefaultCompletionTokens());
    }

    @Bean
    ChatTenancy chatTenancy(
            TenancyProperties properties,
            PriceCatalog prices,
            ModelRouter router,
            GatewayMetrics metrics,
            TokenEstimator estimator,
            ObjectProvider<RequestLimiter> limiter,
            ObjectProvider<BudgetCounter> budgets,
            ObjectProvider<UsageLedger> ledger
    ) {
        if (!properties.isEnabled()) {
            return new NoopChatTenancy();
        }
        return new ActiveChatTenancy(
                limiter.getObject(),
                budgets.getObject(),
                ledger.getObject(),
                router,
                estimator,
                prices,
                metrics);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "gateway.tenancy", name = "enabled", havingValue = "true")
    @EnableScheduling
    @EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
    static class Infrastructure {

        private static final Logger log = LoggerFactory.getLogger(Infrastructure.class);

        @Bean
        R2dbcApiKeyDirectory r2dbcApiKeyDirectory(DatabaseClient databaseClient) {
            return new R2dbcApiKeyDirectory(databaseClient);
        }

        @Bean
        TenantAuthenticator tenantAuthenticator(R2dbcApiKeyDirectory directory, TenancyProperties properties) {
            return new TenantAuthenticator(new CachingApiKeyDirectory(
                    directory,
                    properties.getCacheTtl(),
                    properties.getCacheMaximumSize()));
        }

        @Bean
        RequestLimiter requestLimiter(ReactiveStringRedisTemplate redis) {
            return new RedisTokenBucket(redis);
        }

        @Bean
        BudgetCounter budgetCounter(ReactiveStringRedisTemplate redis) {
            return new RedisBudgetCounter(redis);
        }

        @Bean
        UsageLedgerEvents usageLedgerEvents(GatewayMetrics metrics) {
            return new UsageLedgerEvents() {
                @Override
                public void succeeded() {
                    metrics.recordLedgerWrite(true);
                }

                @Override
                public void failed(Throwable error) {
                    metrics.recordLedgerWrite(false);
                }
            };
        }

        @Bean(destroyMethod = "close")
        UsageLedger usageLedger(DatabaseClient databaseClient, LedgerProperties ledgerProperties, UsageLedgerEvents events) {
            R2dbcUsageLedgerWriter writer = new R2dbcUsageLedgerWriter(databaseClient);
            return new AsyncUsageLedger(ledgerProperties.getBufferSize(), writer::insert, events);
        }

        @Bean
        LedgerMaintenanceJob ledgerMaintenanceJob(DatabaseClient databaseClient, LedgerProperties properties) {
            return new LedgerMaintenanceJob(databaseClient, properties.getRetention(), properties.getPrecreateDays());
        }

        @Bean
        LockProvider lockProvider(ConnectionFactory connectionFactory) {
            return new R2dbcLockProvider(connectionFactory);
        }

        @Bean
        LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
            return new DefaultLockingTaskExecutor(lockProvider);
        }

        @Bean
        ChatTenancyWebFilter chatTenancyWebFilter(
                TenantAuthenticator authenticator,
                RequestLimiter limiter,
                UsageLedger ledger,
                GatewayMetrics metrics,
                ObjectMapper objectMapper,
                ObservationRegistry observations
        ) {
            return new ChatTenancyWebFilter(authenticator, limiter, ledger, metrics, objectMapper, observations);
        }

        @Bean
        @Order(0)
        ApplicationRunner tenantBootstrap(DatabaseClient databaseClient, TenancyProperties properties) {
            List<TenantSeed> seeds = seeds(properties);
            TenantBootstrap bootstrap = new TenantBootstrap(databaseClient, seeds);
            return args -> bootstrap.seed().block(Duration.ofSeconds(30));
        }

        @Bean
        @Order(1)
        ApplicationRunner ledgerPartitions(LedgerMaintenanceJob job, LockingTaskExecutor executor) {
            return args -> {
                try {
                    LockingTaskExecutor.TaskResult<Boolean> result = executor.executeWithLock(
                            () -> {
                                job.maintain().block(Duration.ofMinutes(2));
                                return Boolean.TRUE;
                            },
                            new LockConfiguration(
                                    Instant.now(),
                                    "usageLedgerRetention",
                                    Duration.ofMinutes(10),
                                    Duration.ofSeconds(1)));
                    if (!result.wasExecuted()) {
                        log.info("usage ledger partition maintenance skipped; another instance holds the lock");
                    }
                } catch (Throwable ex) {
                    throw new IllegalStateException("usage ledger partition maintenance failed", ex);
                }
            };
        }
    }

    static List<TenantSeed> seeds(TenancyProperties properties) {
        List<TenantSeed> seeds = new ArrayList<>();
        for (TenancyProperties.BootstrapTenant tenant : properties.getBootstrap()) {
            if (tenant.getTenantId() == null || tenant.getTenantId().isBlank()) {
                continue;
            }
            ApiKeyRecord.TenantStatus status = parseStatus(tenant.getTenantId(), tenant.getStatus());
            String displayName = tenant.getDisplayName() == null || tenant.getDisplayName().isBlank()
                    ? tenant.getTenantId()
                    : tenant.getDisplayName();
            TenancyProperties.BootstrapLimits limits = tenant.getLimits() == null
                    ? new TenancyProperties.BootstrapLimits()
                    : tenant.getLimits();
            List<TenancyProperties.BootstrapKey> keys = tenant.getKeys();
            if (keys == null || keys.isEmpty()) {
                seeds.add(seed(tenant.getTenantId(), displayName, status, limits, "", ""));
            } else {
                for (TenancyProperties.BootstrapKey key : keys) {
                    seeds.add(seed(
                            tenant.getTenantId(),
                            displayName,
                            status,
                            limits,
                            key.getKeyId() == null ? "" : key.getKeyId(),
                            key.getSecret() == null ? "" : key.getSecret()));
                }
            }
        }
        return List.copyOf(seeds);
    }

    private static TenantSeed seed(
            String tenantId,
            String displayName,
            ApiKeyRecord.TenantStatus status,
            TenancyProperties.BootstrapLimits limits,
            String keyId,
            String secret
    ) {
        return new TenantSeed(
                tenantId.trim(),
                displayName,
                status,
                keyId == null ? "" : keyId.trim(),
                secret == null ? "" : secret.trim(),
                limits.getRateCapacity(),
                limits.getRateRefillPerSecond(),
                parsePositive(limits.getTokensPerMinute()),
                parseBudget(limits.getTokenBudget()),
                parseBudget(limits.getCostBudgetMicros()),
                limits.getBudgetWindow());
    }

    private static ApiKeyRecord.TenantStatus parseStatus(String tenantId, String status) {
        if (status == null || status.isBlank() || "active".equalsIgnoreCase(status)) {
            return ApiKeyRecord.TenantStatus.ACTIVE;
        }
        if ("disabled".equalsIgnoreCase(status)) {
            return ApiKeyRecord.TenantStatus.DISABLED;
        }
        throw new IllegalStateException("bootstrap tenant " + tenantId + " has status " + status);
    }

    /** Zero or blank disables the optional tokens-per-minute bucket. */
    private static Long parsePositive(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        long value = Long.parseLong(raw.trim());
        return value <= 0 ? null : value;
    }

    /** Blank means no budget. Zero is a hard cap. */
    private static Long parseBudget(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Long.parseLong(raw.trim());
    }
}
