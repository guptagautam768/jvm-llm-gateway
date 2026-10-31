package dev.gautamgupta.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.gautamgupta.gateway.cache.CacheBypass;
import dev.gautamgupta.gateway.tenancy.budget.BudgetWindow;
import dev.gautamgupta.gateway.web.ChatCompletionsController;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
 * AC-006, AC-007, and AC-008 on the chat route, plus tenant isolation, the bypass
 * header, TTL, the temperature gate, and ledger / budget behaviour for a hit.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.tenancy.enabled=true",
                "spring.flyway.enabled=true",
                "gateway.tenancy.cache-ttl=0s",
                "gateway.cache.enabled=true",
                "gateway.cache.exact.ttl=10m",
                "gateway.cache.exact.max-entries=1000",
                "gateway.cache.semantic.enabled=true",
                "gateway.cache.semantic.ttl=10m",
                "gateway.cache.semantic.max-entries=1000",
                "gateway.cache.semantic.threshold=0.92",
                "gateway.cache.semantic.max-temperature=0.2",
                "gateway.cache.semantic.dimensions=4",
                "gateway.cache.semantic.embedding-provider=openai",
                "gateway.cache.semantic.embedding-model=text-embedding-3-small",
                "gateway.prices.gpt-4o-mini.prompt-per-1k-usd=0.002",
                "gateway.prices.gpt-4o-mini.completion-per-1k-usd=0.008",
                "management.server.port=0",
                "management.prometheus.metrics.export.enabled=true",
                "logging.level.dev.gautamgupta.gateway.cache=INFO"
        })
@Testcontainers
class ResponseCacheIT {

    private static final WireMockServer WIRE_MOCK = startWireMock();
    private static final String NEAR = "[0.95,0.3122,0,0]";
    private static final String AXIS_X = "[1,0,0,0]";
    private static final String AXIS_Y = "[0,1,0,0]";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis/redis-stack-server:7.4.0-v8"))
            .withExposedPorts(6379);

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private DatabaseClient database;

    @Autowired
    private ReactiveStringRedisTemplate redis;

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
        List<String> keys = redis.keys("cache:*").collectList().block(Duration.ofSeconds(5));
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys.toArray(String[]::new)).block(Duration.ofSeconds(5));
        }
        redis.delete("rl:burst").block(Duration.ofSeconds(5));
        webTestClient = webTestClient.mutate().responseTimeout(Duration.ofSeconds(20)).build();
        managementClient = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + managementPort)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    void identicalRequestIsAnExactHitAndDoesNotSpendBudgetAgain() {
        stubChat("Alpha");
        String body = chatBody(null, "Say alpha", null);

        chat(token("acmekey"), body, "exact-1", null)
                .expectStatus().isOk()
                .expectHeader().valueEquals(ChatCompletionsController.CACHE_HEADER, "miss")
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("Alpha");
        String spent = budgetTokens("acme");
        assertThat(spent).isNotNull();
        assertThat(awaitLedger("exact-1").get("cache")).isEqualTo("none");
        assertThat(((Number) awaitLedger("exact-1").get("est_cost_micros")).longValue()).isEqualTo(66L);

        stubChat("Beta");
        chat(token("acmekey"), body, "exact-2", null)
                .expectStatus().isOk()
                .expectHeader().valueEquals(ChatCompletionsController.CACHE_HEADER, "exact")
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("Alpha");

        assertThat(calls("/v1/chat/completions")).isEqualTo(1);
        assertThat(calls("/v1/embeddings")).isZero();
        assertThat(budgetTokens("acme")).isEqualTo(spent);
        Map<String, Object> hit = awaitLedger("exact-2");
        assertThat(hit.get("cache")).isEqualTo("exact");
        assertThat(hit.get("provider")).isEqualTo("openai");
        assertThat(hit.get("model")).isEqualTo("gpt-4o-mini");
        assertThat(((Number) hit.get("prompt_tokens")).intValue()).isEqualTo(5);
        assertThat(((Number) hit.get("est_cost_micros")).longValue()).isEqualTo(66L);
        assertThat(prometheus()).contains("gateway_cache_total")
                .contains("result=\"hit\"")
                .contains("type=\"exact\"");
    }

    @Test
    void semanticHitAndMissFollowTheThreshold() {
        stubEmbedding("capital of France", AXIS_X);
        stubEmbedding("France's capital city", NEAR);
        stubEmbedding("depth of the ocean", AXIS_Y);
        stubChat("Paris");

        chat(token("acmekey"), chatBody("Be brief", "capital of France", 0.0), "sem-1", null)
                .expectStatus().isOk()
                .expectHeader().valueEquals(ChatCompletionsController.CACHE_HEADER, "miss")
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("Paris");
        String spent = budgetTokens("acme");
        assertThat(spent).isNotNull();

        stubChat("Should not be called");
        chat(token("acmekey"), chatBody("Be brief", "France's capital city", 0.0), "sem-2", null)
                .expectStatus().isOk()
                .expectHeader().valueEquals(ChatCompletionsController.CACHE_HEADER, "semantic")
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("Paris");
        assertThat(calls("/v1/chat/completions")).isEqualTo(1);
        assertThat(budgetTokens("acme")).isEqualTo(spent);
        assertThat(awaitLedger("sem-2").get("cache")).isEqualTo("semantic");

        stubChat("Deep");
        chat(token("acmekey"), chatBody("Be brief", "depth of the ocean", 0.0), "sem-3", null)
                .expectStatus().isOk()
                .expectHeader().valueEquals(ChatCompletionsController.CACHE_HEADER, "miss")
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("Deep");
        assertThat(calls("/v1/chat/completions")).isEqualTo(2);

        stubChat("Lyon");
        chat(token("acmekey"), chatBody("Be verbose", "France's capital city", 0.0), "sem-4", null)
                .expectStatus().isOk()
                .expectHeader().valueEquals(ChatCompletionsController.CACHE_HEADER, "miss")
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("Lyon");
        assertThat(calls("/v1/chat/completions")).isEqualTo(3);
        assertThat(prometheus()).contains("type=\"semantic\"");
    }

    @Test
    void warmTemperatureDoesNotUseTheSemanticCache() {
        stubEmbedding("capital of France", AXIS_X);
        stubEmbedding("France's capital city", NEAR);
        stubChat("Paris");
        chat(token("acmekey"), chatBody(null, "capital of France", 0.0), "warm-1", null)
                .expectStatus().isOk();

        stubChat("Ocean");
        chat(token("acmekey"), chatBody(null, "France's capital city", 1.0), "warm-2", null)
                .expectStatus().isOk()
                .expectHeader().valueEquals(ChatCompletionsController.CACHE_HEADER, "miss")
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("Ocean");
        assertThat(calls("/v1/chat/completions")).isEqualTo(2);
        assertThat(calls("/v1/embeddings")).isEqualTo(1);
    }

    @Test
    void bypassSkipsLookupAndWrite() {
        stubChat("Paris");
        String body = chatBody(null, "Bypass me", null);
        chat(token("acmekey"), body, "bypass-1", null)
                .expectStatus().isOk()
                .expectHeader().valueEquals(ChatCompletionsController.CACHE_HEADER, "miss");

        stubChat("Lyon");
        chat(token("acmekey"), body, "bypass-2", "TRUE")
                .expectStatus().isOk()
                .expectHeader().valueEquals(ChatCompletionsController.CACHE_HEADER, "bypass")
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("Lyon");
        assertThat(calls("/v1/chat/completions")).isEqualTo(2);

        chat(token("acmekey"), body, "bypass-3", null)
                .expectStatus().isOk()
                .expectHeader().valueEquals(ChatCompletionsController.CACHE_HEADER, "exact")
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("Paris");
        assertThat(calls("/v1/chat/completions")).isEqualTo(2);
        assertThat(awaitLedger("bypass-2").get("cache")).isEqualTo("none");
    }

    @Test
    void tenantsDoNotShareAnExactEntry() {
        String body = chatBody(null, "Shared words", null);
        stubChat("From acme");
        chat(token("acmekey"), body, "tenant-a", null)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("From acme");

        stubChat("From beta");
        chat(token("betakey"), body, "tenant-b", null)
                .expectStatus().isOk()
                .expectHeader().valueEquals(ChatCompletionsController.CACHE_HEADER, "miss")
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("From beta");

        chat(token("acmekey"), body, "tenant-a2", null)
                .expectStatus().isOk()
                .expectHeader().valueEquals(ChatCompletionsController.CACHE_HEADER, "exact")
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("From acme");
        assertThat(calls("/v1/chat/completions")).isEqualTo(2);
    }

    @Test
    void expiredExactEntryMisses() {
        stubChat("One");
        String body = chatBody(null, "Expires", null);
        chat(token("acmekey"), body, "ttl-1", null)
                .expectStatus().isOk()
                .expectHeader().valueEquals(ChatCompletionsController.CACHE_HEADER, "miss");
        List<String> keys = redis.keys("cache:x:*").collectList().block(Duration.ofSeconds(5));
        assertThat(keys).isNotEmpty();
        for (String key : keys) {
            redis.expire(key, Duration.ofSeconds(1)).block(Duration.ofSeconds(5));
        }
        stubChat("Two");
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> chat(token("acmekey"), body, "ttl-2", null)
                .expectStatus().isOk()
                .expectHeader().valueEquals(ChatCompletionsController.CACHE_HEADER, "miss")
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("Two"));
    }

    @Test
    void cacheHitStillRequiresTheRateLimitAndAValidKey() {
        stubChat("Ok");
        String body = chatBody(null, "Limited", null);
        chat(token("burstkey"), body, "limit-1", null)
                .expectStatus().isOk()
                .expectHeader().valueEquals(ChatCompletionsController.CACHE_HEADER, "miss");
        chat(token("burstkey"), body, "limit-2", null)
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("rate_limit_exceeded");
        assertThat(calls("/v1/chat/completions")).isEqualTo(1);

        chat(null, body, "limit-3", null)
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_api_key");
        assertThat(calls("/v1/chat/completions")).isEqualTo(1);
    }

    @Test
    void streamingSkipsTheCache() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("""
                                data: {"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":"Hi"}}]}

                                data: [DONE]

                                """)));
        String body = "{\"model\":\"gpt-4o-mini\",\"stream\":true,\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"Count\"}]}";
        for (String requestId : List.of("stream-1", "stream-2")) {
            String payload = webTestClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("acmekey"))
                    .header("X-Request-Id", requestId)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(body)
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals(ChatCompletionsController.CACHE_HEADER, "skip")
                    .returnResult(String.class)
                    .getResponseBody()
                    .collectList()
                    .map(parts -> String.join("", parts))
                    .block(Duration.ofSeconds(15));
            assertThat(payload).contains("Hi").contains("[DONE]");
        }
        assertThat(calls("/v1/chat/completions")).isEqualTo(2);
        assertThat(awaitLedger("stream-2").get("cache")).isEqualTo("none");
    }

    private WebTestClient.ResponseSpec chat(String presented, String body, String requestId, String bypass) {
        WebTestClient.RequestBodySpec spec = webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", requestId);
        if (presented != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + presented);
        }
        if (bypass != null) {
            spec = spec.header(CacheBypass.HEADER, bypass);
        }
        return spec.bodyValue(body).exchange();
    }

    private Map<String, Object> awaitLedger(String requestId) {
        Map<String, Object>[] found = new Map[1];
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            Map<String, Object> row = database.sql("""
                            SELECT provider, model, prompt_tokens, completion_tokens, est_cost_micros, cache, status
                            FROM usage_ledger
                            WHERE request_id = :id
                            """)
                    .bind("id", requestId)
                    .map(result -> {
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("provider", result.get("provider", String.class));
                        values.put("model", result.get("model", String.class));
                        values.put("prompt_tokens", result.get("prompt_tokens"));
                        values.put("completion_tokens", result.get("completion_tokens"));
                        values.put("est_cost_micros", result.get("est_cost_micros"));
                        values.put("cache", result.get("cache", String.class));
                        values.put("status", result.get("status"));
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

    private long calls(String path) {
        return WIRE_MOCK.getAllServeEvents().stream()
                .filter(event -> path.equals(event.getRequest().getUrl()))
                .count();
    }

    private void stubChat(String content) {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"chatcmpl-test","object":"chat.completion","created":1,"model":"gpt-4o-mini",
                                 "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                                 "usage":{"prompt_tokens":5,"completion_tokens":7,"total_tokens":12}}
                                """.formatted(content))));
    }

    private void stubEmbedding(String input, String vector) {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/embeddings"))
                .withRequestBody(matchingJsonPath("$.input", equalTo(input)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"object":"list","data":[{"object":"embedding","index":0,"embedding":%s}],
                                 "model":"text-embedding-3-small","usage":{"prompt_tokens":1,"total_tokens":1}}
                                """.formatted(vector))));
    }

    private static String chatBody(String system, String user, Double temperature) {
        String temp = temperature == null ? "" : ",\"temperature\":" + temperature;
        String systemMessage = system == null ? "" : "{\"role\":\"system\",\"content\":" + quote(system) + "},";
        return "{\"model\":\"gpt-4o-mini\",\"max_tokens\":16" + temp
                + ",\"messages\":[" + systemMessage + "{\"role\":\"user\",\"content\":" + quote(user) + "}]}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void registerTenants(DynamicPropertyRegistry registry) {
        List<TenantSpec> tenants = List.of(
                new TenantSpec("acme", "acmekey", 1000, 1000),
                new TenantSpec("beta", "betakey", 1000, 1000),
                new TenantSpec("burst", "burstkey", 1, 0.001));
        for (int i = 0; i < tenants.size(); i++) {
            TenantSpec tenant = tenants.get(i);
            String prefix = "gateway.tenancy.bootstrap[" + i + "]";
            registry.add(prefix + ".tenant-id", tenant::id);
            registry.add(prefix + ".display-name", tenant::id);
            registry.add(prefix + ".status", () -> "active");
            registry.add(prefix + ".keys[0].key-id", tenant::keyId);
            registry.add(prefix + ".keys[0].secret", () -> secret(tenant.keyId()));
            registry.add(prefix + ".limits.rate-capacity", () -> Integer.toString(tenant.capacity()));
            registry.add(prefix + ".limits.rate-refill-per-second", () -> Double.toString(tenant.refill()));
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

    private record TenantSpec(String id, String keyId, int capacity, double refill) {
    }
}
