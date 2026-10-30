package dev.gautamgupta.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.gautamgupta.gateway.persistence.ledger.LedgerMaintenanceJob;
import dev.gautamgupta.gateway.persistence.ledger.PartitionPlan;
import dev.gautamgupta.gateway.tenancy.budget.BudgetWindow;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * AC-009 and AC-012 against real Postgres 16 and Redis Stack: auth, token bucket,
 * budgets, ledger rows, and the retention job.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.tenancy.enabled=true",
                "spring.flyway.enabled=true",
                "gateway.tenancy.cache-ttl=0s",
                "gateway.tenancy.metrics-tenant-tag=true",
                "gateway.tenancy.default-completion-tokens=16",
                "gateway.ledger.retention=30d",
                "gateway.ledger.precreate-days=3",
                "gateway.ledger.retention-cron=0 0 0 1 1 *",
                "management.server.port=0",
                "management.prometheus.metrics.export.enabled=true",
                "management.health.redis.enabled=true",
                "management.health.r2dbc.enabled=true",
                "management.health.db.enabled=true",
                "gateway.prices.gpt-4o-mini.prompt-per-1k-usd=0.002",
                "gateway.prices.gpt-4o-mini.completion-per-1k-usd=0.008",
                "gateway.routes.gap.targets[0].provider=openai",
                "gateway.routes.gap.targets[0].model=gpt-test-unpriced",
                "gateway.providers.openai.response-timeout=5s",
                "gateway.providers.anthropic.response-timeout=5s"
        }
)
@Testcontainers
class TenancyLedgerIT {

    private static final WireMockServer WIRE_MOCK = startWireMock();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis/redis-stack-server:7.4.0-v8"))
            .withExposedPorts(6379);

    private static final String CHAT = """
            {"model":"gpt-4o-mini","max_tokens":16,"messages":[{"role":"user","content":"Hello"}]}
            """;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private DatabaseClient database;

    @Autowired
    private ReactiveStringRedisTemplate redis;

    @Autowired
    private LedgerMaintenanceJob retention;

    @LocalManagementPort
    private int managementPort;

    private WebTestClient managementClient;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("gateway.providers.openai.base-url", () -> "http://127.0.0.1:" + WIRE_MOCK.port());
        registry.add("gateway.providers.openai.api-key", () -> "sk-test");
        registry.add("gateway.providers.anthropic.base-url", () -> "http://127.0.0.1:" + WIRE_MOCK.port());
        registry.add("gateway.providers.anthropic.api-key", () -> "sk-ant-test");
        registerTenants(registry);
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @BeforeEach
    void reset() {
        WIRE_MOCK.resetAll();
        webTestClient = webTestClient.mutate().responseTimeout(Duration.ofSeconds(20)).build();
        managementClient = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + managementPort)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    void unknownAndMalformedKeysAre401() {
        chat(null, CHAT, "req-missing")
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_api_key")
                .jsonPath("$.error.type").isEqualTo("invalid_request_error");
        chat("not-a-gateway-key", CHAT, "req-bad")
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_api_key");
        chat(token("acmekey").replace(secret("acmekey"), secret("other")), CHAT, "req-wrong")
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_api_key");
        assertThat(WIRE_MOCK.getAllServeEvents()).isEmpty();
    }

    @Test
    void revokedKeyIs401() {
        database.sql("UPDATE api_key SET status = 'revoked', revoked_at = now() WHERE key_id = 'revokedkey'")
                .then()
                .block(Duration.ofSeconds(5));

        chat(token("revokedkey"), CHAT, "req-revoked")
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_api_key");
    }

    @Test
    void disabledTenantIs403() {
        chat(token("disabledkey"), CHAT, "req-disabled")
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("tenant_disabled")
                .jsonPath("$.error.type").isEqualTo("invalid_request_error");
        assertThat(WIRE_MOCK.getAllServeEvents()).isEmpty();
    }

    @Test
    void apiKeyHeaderReachesTheProvider() {
        stubChat("{\"prompt_tokens\":5,\"completion_tokens\":7,\"total_tokens\":12}");

        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("api-key", token("acmekey"))
                .header("X-Request-Id", "req-header")
                .bodyValue(CHAT)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Limit", "1000")
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("ok");
    }

    @Test
    void ledgerRowHasTenantTokensCostAndCacheFlag() {
        stubChat("{\"prompt_tokens\":5,\"completion_tokens\":7,\"total_tokens\":12}");

        chat(token("acmekey"), CHAT, "req-ledger")
                .expectStatus().isOk();

        Map<String, Object> row = awaitLedger("req-ledger");
        assertThat(row.get("tenant_id")).isEqualTo("acme");
        assertThat(row.get("route")).isEqualTo("chat.completions");
        assertThat(row.get("provider")).isEqualTo("openai");
        assertThat(row.get("model")).isEqualTo("gpt-4o-mini");
        assertThat(((Number) row.get("prompt_tokens")).intValue()).isEqualTo(5);
        assertThat(((Number) row.get("completion_tokens")).intValue()).isEqualTo(7);
        assertThat(((Number) row.get("est_cost_micros")).longValue()).isEqualTo(66L);
        assertThat(row.get("cache")).isEqualTo("none");
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(200);
        assertThat(row.get("error_code")).isNull();

        List<String> columns = database.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'usage_ledger'
                        """)
                .map(result -> result.get("column_name", String.class))
                .all()
                .collectList()
                .block(Duration.ofSeconds(5));
        assertThat(columns).contains("tenant_id", "prompt_tokens", "completion_tokens", "est_cost_micros", "cache")
                .doesNotContain("prompt", "completion", "messages", "content");
    }

    @Test
    void rateLimitReturns429WithHeaders() {
        stubChat("{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}");

        chat(token("burstkey"), CHAT, "req-burst-1")
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Limit", "1")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0");

        chat(token("burstkey"), CHAT, "req-burst-2")
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("X-RateLimit-Limit", "1")
                .expectHeader().exists("Retry-After")
                .expectHeader().exists("X-RateLimit-Reset")
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("rate_limit_exceeded")
                .jsonPath("$.error.type").isEqualTo("rate_limit_error");

        assertThat(WIRE_MOCK.getAllServeEvents()).hasSize(1);
        Map<String, Object> row = awaitLedger("req-burst-2");
        assertThat(row.get("tenant_id")).isEqualTo("burst");
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(429);
        assertThat(row.get("error_code")).isEqualTo("rate_limit_exceeded");

        String scrape = prometheus();
        assertThat(scrape).contains("gateway_ratelimit_rejections_total");
        assertThat(scrape).contains("reason=\"rate_limit_exceeded\"");
        assertThat(scrape).contains("tenant=\"burst\"");
    }

    @Test
    void tokenAndCostBudgetsReturn429() {
        chat(token("tokenkey"), CHAT, "req-tokens")
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("token_budget_exceeded");

        chat(token("costkey"), CHAT, "req-cost")
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("cost_budget_exceeded");

        assertThat(WIRE_MOCK.getAllServeEvents()).isEmpty();
        assertThat(awaitLedger("req-tokens").get("error_code")).isEqualTo("token_budget_exceeded");
        assertThat(awaitLedger("req-cost").get("error_code")).isEqualTo("cost_budget_exceeded");
    }

    @Test
    void reconcileUsesActualUsageRatherThanTheReservation() {
        stubChat("{\"prompt_tokens\":5,\"completion_tokens\":7,\"total_tokens\":12}");
        String body = """
                {"model":"gpt-4o-mini","max_tokens":400,"messages":[{"role":"user","content":"Hello"}]}
                """;

        chat(token("reconcilekey"), body, "req-reconcile")
                .expectStatus().isOk();

        assertThat(budgetTokens("reconcile")).isEqualTo("12");
        assertThat(((Number) awaitLedger("req-reconcile").get("est_cost_micros")).longValue()).isEqualTo(66L);
    }

    @Test
    void streamingReconcilesReportedUsage() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("""
                                data: {"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"}}]}

                                data: {"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}

                                data: [DONE]

                                """)));

        String body = webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("streamkey"))
                .header("X-Request-Id", "req-stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("""
                        {"model":"gpt-4o-mini","stream":true,"max_tokens":400,"messages":[{"role":"user","content":"Hello"}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .map(parts -> String.join("", parts))
                .block(Duration.ofSeconds(15));

        assertThat(body).contains("Hi").contains("[DONE]");
        assertThat(budgetTokens("stream")).isEqualTo("6");
        Map<String, Object> row = awaitLedger("req-stream");
        assertThat(((Number) row.get("prompt_tokens")).intValue()).isEqualTo(4);
        assertThat(((Number) row.get("completion_tokens")).intValue()).isEqualTo(2);
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(200);
    }

    @Test
    void missingPriceStoresNullCostAndIncrementsTheMetric() {
        stubChat("{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}");
        String body = """
                {"model":"gpt-test-unpriced","messages":[{"role":"user","content":"Hello"}]}
                """;

        chat(token("nopricekey"), body, "req-noprice")
                .expectStatus().isOk();

        Map<String, Object> row = awaitLedger("req-noprice");
        assertThat(row.get("model")).isEqualTo("gpt-test-unpriced");
        assertThat(row.get("est_cost_micros")).isNull();
        assertThat(((Number) row.get("prompt_tokens")).intValue()).isEqualTo(3);
        assertThat(prometheus()).contains("gateway_price_missing_total")
                .contains("model=\"gpt-test-unpriced\"");
    }

    @Test
    void retentionDropsOldPartitionsAndPrecreatesUpcomingOnes() {
        database.sql("""
                        CREATE TABLE IF NOT EXISTS usage_ledger_p20200101 PARTITION OF usage_ledger
                        FOR VALUES FROM ('2020-01-01 00:00:00+00') TO ('2020-01-02 00:00:00+00')
                        """)
                .then()
                .block(Duration.ofSeconds(10));

        retention.maintain().block(Duration.ofSeconds(30));

        assertThat(relationCount("usage_ledger_p20200101")).isZero();
        String upcoming = PartitionPlan.name(LocalDate.now(ZoneOffset.UTC).plusDays(3));
        assertThat(relationCount(upcoming)).isEqualTo(1);
    }

    @Test
    void shedLockGuardsTheRetentionJob() {
        retention.scheduled();
        Long locks = database.sql("SELECT count(*) FROM shedlock WHERE name = 'usageLedgerRetention'")
                .map(row -> row.get(0, Long.class))
                .one()
                .block(Duration.ofSeconds(5));
        assertThat(locks).isEqualTo(1);
    }

    @Test
    void modelsDoNotRequireACallerKey() {
        webTestClient.get()
                .uri("/v1/models")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list");

        managementClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    private WebTestClient.ResponseSpec chat(String presented, String body, String requestId) {
        WebTestClient.RequestBodySpec spec = webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", requestId);
        if (presented != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + presented);
        }
        return spec.bodyValue(body).exchange();
    }

    private Map<String, Object> awaitLedger(String requestId) {
        Map<String, Object>[] found = new Map[1];
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            Map<String, Object> row = database.sql("""
                            SELECT tenant_id, route, provider, model, prompt_tokens, completion_tokens,
                                   est_cost_micros, cache, status, error_code
                            FROM usage_ledger
                            WHERE request_id = :id
                            """)
                    .bind("id", requestId)
                    .map(result -> {
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("tenant_id", result.get("tenant_id", String.class));
                        values.put("route", result.get("route", String.class));
                        values.put("provider", result.get("provider", String.class));
                        values.put("model", result.get("model", String.class));
                        values.put("prompt_tokens", result.get("prompt_tokens"));
                        values.put("completion_tokens", result.get("completion_tokens"));
                        values.put("est_cost_micros", result.get("est_cost_micros"));
                        values.put("cache", result.get("cache", String.class));
                        values.put("status", result.get("status"));
                        values.put("error_code", result.get("error_code", String.class));
                        return values;
                    })
                    .one()
                    .block(Duration.ofSeconds(2));
            assertThat(row).isNotNull();
            found[0] = row;
        });
        return found[0];
    }

    private String budgetTokens(String tenantId) {
        long start = BudgetWindow.startEpochSeconds(Instant.now(), Duration.ofDays(30));
        Object value = redis.opsForHash()
                .get("budget:" + tenantId + ":" + start, "tokens")
                .block(Duration.ofSeconds(5));
        return value == null ? null : value.toString();
    }

    private long relationCount(String name) {
        Number count = database.sql("SELECT count(*) FROM pg_class WHERE relname = :name")
                .bind("name", name)
                .map(row -> (Number) row.get(0))
                .one()
                .block(Duration.ofSeconds(5));
        return count == null ? 0 : count.longValue();
    }

    private String prometheus() {
        return managementClient.get()
                .uri("/actuator/prometheus")
                .header(HttpHeaders.ACCEPT, "text/plain;version=0.0.4;charset=utf-8,*/*;q=0.1")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private void stubChat(String usageJson) {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "chatcmpl-test",
                                  "object": "chat.completion",
                                  "created": 1,
                                  "model": "gpt-4o-mini",
                                  "choices": [{
                                    "index": 0,
                                    "message": {"role": "assistant", "content": "ok"},
                                    "finish_reason": "stop"
                                  }],
                                  "usage": %s
                                }
                                """.formatted(usageJson))));
    }

    private static void registerTenants(DynamicPropertyRegistry registry) {
        List<TenantSpec> tenants = List.of(
                new TenantSpec("acme", "acmekey", "active", 1000, 1000, "", "", ""),
                new TenantSpec("burst", "burstkey", "active", 1, 0.001, "", "", ""),
                new TenantSpec("tokenpoor", "tokenkey", "active", 100, 100, "", "1", ""),
                new TenantSpec("costpoor", "costkey", "active", 100, 100, "", "", "1"),
                new TenantSpec("disabledco", "disabledkey", "disabled", 100, 100, "", "", ""),
                new TenantSpec("revokedco", "revokedkey", "active", 100, 100, "", "", ""),
                new TenantSpec("stream", "streamkey", "active", 1000, 1000, "", "", ""),
                new TenantSpec("reconcile", "reconcilekey", "active", 1000, 1000, "", "", ""),
                new TenantSpec("noprice", "nopricekey", "active", 1000, 1000, "", "", ""));
        for (int i = 0; i < tenants.size(); i++) {
            TenantSpec tenant = tenants.get(i);
            String prefix = "gateway.tenancy.bootstrap[" + i + "]";
            registry.add(prefix + ".tenant-id", tenant::id);
            registry.add(prefix + ".display-name", tenant::id);
            registry.add(prefix + ".status", tenant::status);
            registry.add(prefix + ".keys[0].key-id", tenant::keyId);
            registry.add(prefix + ".keys[0].secret", () -> secret(tenant.keyId()));
            registry.add(prefix + ".limits.rate-capacity", () -> Integer.toString(tenant.capacity()));
            registry.add(prefix + ".limits.rate-refill-per-second", () -> Double.toString(tenant.refill()));
            registry.add(prefix + ".limits.tokens-per-minute", tenant::tokensPerMinute);
            registry.add(prefix + ".limits.token-budget", tenant::tokenBudget);
            registry.add(prefix + ".limits.cost-budget-micros", tenant::costBudget);
            registry.add(prefix + ".limits.budget-window", () -> "30d");
        }
    }

    private static String token(String keyId) {
        return "lgw_" + keyId + "_" + secret(keyId);
    }

    private static String secret(String label) {
        byte[] raw = new byte[32];
        byte[] labelBytes = label.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(labelBytes, 0, raw, 0, Math.min(labelBytes.length, raw.length));
        for (int i = labelBytes.length; i < raw.length; i++) {
            raw[i] = (byte) (i + 1);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static WireMockServer startWireMock() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    private record TenantSpec(
            String id,
            String keyId,
            String status,
            int capacity,
            double refill,
            String tokensPerMinute,
            String tokenBudget,
            String costBudget
    ) {
    }
}
