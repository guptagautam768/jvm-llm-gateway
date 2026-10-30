package dev.gautamgupta.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.TextNode;
import dev.gautamgupta.gateway.api.Usage;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.api.chat.ChatCompletionResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

/**
 * Exact and semantic cache against Redis Stack: tenant isolation, cosine threshold,
 * the context tag, TTL, and the LRU caps.
 */
@Testcontainers
class RedisResponseCacheIT {

    private static final float[] AXIS_X = {1f, 0f, 0f, 0f};
    private static final float[] NEAR_X = {0.95f, 0.3122f, 0f, 0f};
    private static final float[] AXIS_Y = {0f, 1f, 0f, 0f};
    private static final float[] AXIS_Z = {0f, 0f, 1f, 0f};

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis/redis-stack-server:7.4.0-v8"))
            .withExposedPorts(6379);

    private static RedisStackClient client;

    private final StubEmbeddings embeddings = new StubEmbeddings();
    private final ManualClock clock = new ManualClock();
    private RedisResponseCache cache;

    @BeforeAll
    static void connect() {
        client = RedisStackClient.connect(REDIS.getHost(), REDIS.getMappedPort(6379), null, null, 0);
    }

    @AfterAll
    static void shutdown() {
        if (client != null) {
            client.close();
        }
    }

    @BeforeEach
    void clearEntries() {
        client.commands()
                .keys("cache:*".getBytes(StandardCharsets.UTF_8))
                .concatMap(key -> client.commands().del(key))
                .then()
                .block(Duration.ofSeconds(15));
        cache = cache(100, 100, Duration.ofMinutes(10));
        cache.ensureIndex().block(Duration.ofSeconds(15));
        assertThat(cache.semanticReady()).isTrue();
    }

    @Test
    void exactHitStaysInsideTheTenant() {
        CacheQuery acme = query("acme", "Hello", false);
        store(acme, "one");

        CacheRead hit = cache.read(acme).block(Duration.ofSeconds(5));
        CacheRead other = cache.read(query("other", "Hello", false)).block(Duration.ofSeconds(5));

        assertThat(hit.hitType()).isEqualTo("exact");
        assertThat(hit.hit().response().choices().get(0).message().content().asText()).isEqualTo("one");
        assertThat(hit.observations()).containsExactly(new CacheObservation("hit", "exact"));
        assertThat(other.hit()).isNull();
        assertThat(embeddings.calls).isZero();
        assertThat(client.commands()
                .exists(ExactCacheKey.redisKey("acme", digest(acme).exactHash()).getBytes(StandardCharsets.UTF_8))
                .block()).isEqualTo(1L);
    }

    @Test
    void semanticHitFollowsThresholdContextAndTenant() {
        embeddings.vectors.put("alpha", AXIS_X);
        embeddings.vectors.put("near", NEAR_X);
        embeddings.vectors.put("far", AXIS_Y);
        store(query("acme", "brief", "alpha", true), "Paris");

        CacheRead near = cache.read(query("acme", "brief", "near", true)).block(Duration.ofSeconds(5));
        CacheRead far = cache.read(query("acme", "brief", "far", true)).block(Duration.ofSeconds(5));
        CacheRead otherSystem = cache.read(query("acme", "verbose", "near", true)).block(Duration.ofSeconds(5));
        CacheRead otherTenant = cache.read(query("other", "brief", "near", true)).block(Duration.ofSeconds(5));

        assertThat(near.hitType()).isEqualTo("semantic");
        assertThat(near.hit().response().choices().get(0).message().content().asText()).isEqualTo("Paris");
        assertThat(near.observations()).containsExactly(
                new CacheObservation("miss", "exact"),
                new CacheObservation("hit", "semantic"));
        assertThat(far.hit()).isNull();
        assertThat(far.observations()).contains(new CacheObservation("miss", "semantic"));
        assertThat(otherSystem.hit()).isNull();
        assertThat(otherTenant.hit()).isNull();
        assertThat(Cosine.similarity(AXIS_X, NEAR_X)).isGreaterThanOrEqualTo(SemanticThreshold.DEFAULT);
        assertThat(Cosine.similarity(AXIS_X, AXIS_Y)).isLessThan(SemanticThreshold.DEFAULT);
    }

    @Test
    void ttlDropsExactAndSemanticEntries() {
        cache = cache(100, 100, Duration.ofSeconds(1));
        cache.ensureIndex().block(Duration.ofSeconds(15));
        embeddings.vectors.put("alpha", AXIS_X);
        embeddings.vectors.put("near", NEAR_X);
        CacheQuery stored = query("acme", "brief", "alpha", true);
        store(stored, "Paris");
        assertThat(cache.read(query("acme", "brief", "near", true)).block(Duration.ofSeconds(5)).hitType())
                .isEqualTo("semantic");

        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        CacheRead expired = cache.read(query("acme", "brief", "near", true)).block(Duration.ofSeconds(5));
        while (expired.hit() != null && System.nanoTime() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            expired = cache.read(query("acme", "brief", "near", true)).block(Duration.ofSeconds(5));
        }
        assertThat(expired.hit()).isNull();
        assertThat(cache.read(stored).block(Duration.ofSeconds(5)).hit()).isNull();
    }

    @Test
    void capsEvictTheLeastRecentlyUsedExactAndSemanticEntries() {
        RedisResponseCache exact = cache(2, 100, Duration.ofMinutes(10));
        store(exact, query("acme", "a", false), "A");
        clock.tick();
        store(exact, query("acme", "b", false), "B");
        clock.tick();
        store(exact, query("acme", "c", false), "C");

        assertThat(exact.read(query("acme", "a", false)).block(Duration.ofSeconds(5)).hit()).isNull();
        assertThat(exact.read(query("acme", "b", false)).block(Duration.ofSeconds(5)).hitType()).isEqualTo("exact");
        assertThat(exact.read(query("acme", "c", false)).block(Duration.ofSeconds(5)).hitType()).isEqualTo("exact");

        RedisResponseCache semantic = cache(100, 2, Duration.ofMinutes(10));
        semantic.ensureIndex().block(Duration.ofSeconds(15));
        embeddings.vectors.put("vec-a", AXIS_X);
        embeddings.vectors.put("vec-b", AXIS_Y);
        embeddings.vectors.put("vec-c", AXIS_Z);
        embeddings.vectors.put("probe-a", AXIS_X);
        embeddings.vectors.put("probe-c", AXIS_Z);
        store(semantic, query("sem", "brief", "vec-a", true), "A");
        clock.tick();
        store(semantic, query("sem", "brief", "vec-b", true), "B");
        clock.tick();
        store(semantic, query("sem", "brief", "vec-c", true), "C");

        assertThat(semantic.read(query("sem", "brief", "probe-a", true)).block(Duration.ofSeconds(5)).hit()).isNull();
        assertThat(semantic.read(query("sem", "brief", "probe-c", true)).block(Duration.ofSeconds(5)).hitType())
                .isEqualTo("semantic");
    }

    private RedisResponseCache cache(int exactMax, int semanticMax, Duration ttl) {
        return new RedisResponseCache(
                client,
                embeddings,
                new CacheSettings(ttl, exactMax, true, ttl, semanticMax, 4),
                clock);
    }

    private void store(CacheQuery query, String text) {
        store(cache, query, text);
    }

    private void store(RedisResponseCache target, CacheQuery query, String text) {
        CacheRead miss = target.read(query).block(Duration.ofSeconds(5));
        assertThat(miss.hit()).isNull();
        target.write(query, completion(text), miss.embedding()).block(Duration.ofSeconds(5));
    }

    private static CacheQuery query(String tenant, String user, boolean semantic) {
        return query(tenant, "brief", user, semantic);
    }

    private static CacheQuery query(String tenant, String system, String user, boolean semantic) {
        return new CacheQuery(tenant, "fast", "openai", "gpt-4o-mini", chat(system, user), semantic, SemanticThreshold.DEFAULT);
    }

    private static CanonicalRequest.Digests digest(CacheQuery query) {
        return CanonicalRequest.digest(query.route(), query.resolvedProvider(), query.resolvedModel(), query.request());
    }

    private static ChatCompletionRequest chat(String system, String user) {
        return new ChatCompletionRequest(
                "fast",
                List.of(message("system", system), message("user", user)),
                0.0,
                null, null, null, null, null, 16, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static ChatCompletionRequest.ChatMessage message(String role, String content) {
        return new ChatCompletionRequest.ChatMessage(role, TextNode.valueOf(content), null, null, null);
    }

    private static CachedCompletion completion(String text) {
        return new CachedCompletion(
                "openai",
                "gpt-4o-mini",
                new ChatCompletionResponse(
                        "chatcmpl-1",
                        "chat.completion",
                        1L,
                        "fast",
                        List.of(new ChatCompletionResponse.Choice(
                                0,
                                new ChatCompletionResponse.AssistantMessage("assistant", TextNode.valueOf(text), null, null),
                                "stop",
                                null)),
                        new Usage(5, 7, 12),
                        null));
    }

    private static final class StubEmbeddings implements EmbeddingClient {
        private final Map<String, float[]> vectors = new java.util.HashMap<>();
        private int calls;

        @Override
        public Mono<float[]> embed(String text) {
            calls++;
            float[] vector = vectors.get(text);
            if (vector == null) {
                return Mono.error(new IllegalStateException("no vector"));
            }
            return Mono.just(vector);
        }
    }

    private static final class ManualClock implements CacheClock {
        private long now = 1_000_000L;

        @Override
        public long nowMillis() {
            return now;
        }

        void tick() {
            now += 1_000L;
        }
    }
}
