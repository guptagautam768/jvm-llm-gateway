package dev.gautamgupta.gateway.cache;

import com.fasterxml.jackson.core.JacksonException;
import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gautamgupta.gateway.api.json.GatewayJson;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Exact entries are strings with a TTL. Semantic entries are hashes under
 * {@code cache:s:} plus an HNSW index. Both caps evict the least recently used
 * member of a sorted set. A cache failure becomes a miss and is not a client error.
 */
public final class RedisResponseCache implements ResponseCache {

    public static final String INDEX = "gateway:cache:semantic";

    static final byte[] EXACT_LRU = bytes("cache:lru:exact");
    static final byte[] SEMANTIC_LRU = bytes("cache:lru:semantic");

    private static final Logger log = LoggerFactory.getLogger(RedisResponseCache.class);
    private static final CacheObservation EXACT_MISS = new CacheObservation("miss", "exact");
    private static final CacheObservation SEMANTIC_MISS = new CacheObservation("miss", "semantic");

    private final RedisReactiveCommands<byte[], byte[]> commands;
    private final RedisVectors vectors;
    private final EmbeddingClient embeddings;
    private final CacheSettings settings;
    private final CacheClock clock;
    private final ObjectMapper mapper = GatewayJson.create();
    private final AtomicBoolean semanticReady = new AtomicBoolean(false);

    public RedisResponseCache(
            RedisStackClient redis,
            EmbeddingClient embeddings,
            CacheSettings settings,
            CacheClock clock
    ) {
        this.commands = redis.commands();
        this.vectors = new RedisVectors(redis.commands());
        this.embeddings = embeddings;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public boolean semanticReady() {
        return semanticReady.get();
    }

    public void disableSemantic() {
        semanticReady.set(false);
    }

    public Mono<Void> ensureIndex() {
        if (!settings.semanticEnabled() || !embeddings.available()) {
            semanticReady.set(false);
            if (settings.semanticEnabled()) {
                log.warn("semantic cache is off because the embedding provider is not configured");
            }
            return Mono.empty();
        }
        return vectors.createIndex(settings.dimensions())
                .doOnSuccess(ignored -> semanticReady.set(true))
                .onErrorResume(error -> {
                    if (alreadyExists(error)) {
                        semanticReady.set(true);
                        return Mono.empty();
                    }
                    semanticReady.set(false);
                    log.error("semantic cache index was not created ({})", message(error));
                    return Mono.empty();
                });
    }

    @Override
    public Mono<CacheRead> read(CacheQuery query) {
        if (query.tenantId() == null || query.tenantId().isBlank()) {
            return Mono.just(CacheRead.miss(List.of(), null));
        }
        CanonicalRequest.Digests digests = CanonicalRequest.digest(
                query.route(), query.resolvedProvider(), query.resolvedModel(), query.request());
        String exactKey = ExactCacheKey.redisKey(query.tenantId(), digests.exactHash());
        return readExact(exactKey)
                .switchIfEmpty(Mono.defer(() -> readSemantic(query, digests)))
                .onErrorResume(error -> {
                    log.warn("cache read failed ({})", error.getClass().getSimpleName());
                    return Mono.just(CacheRead.miss(List.of(EXACT_MISS), null));
                });
    }

    @Override
    public Mono<Void> write(CacheQuery query, CachedCompletion completion, float[] embedding) {
        if (query.tenantId() == null || query.tenantId().isBlank() || completion == null || completion.response() == null) {
            return Mono.empty();
        }
        CanonicalRequest.Digests digests = CanonicalRequest.digest(
                query.route(), query.resolvedProvider(), query.resolvedModel(), query.request());
        return writeExact(query, digests, completion)
                .then(Mono.defer(() -> writeSemantic(query, digests, completion, embedding)))
                .onErrorResume(error -> {
                    log.warn("cache write failed ({})", error.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    private Mono<CacheRead> readExact(String keyText) {
        byte[] key = bytes(keyText);
        return commands.get(key)
                .flatMap(payload -> {
                    CachedCompletion completion = decode(payload);
                    if (completion == null) {
                        return commands.del(key).then(commands.zrem(EXACT_LRU, key)).then(Mono.empty());
                    }
                    return commands.zadd(EXACT_LRU, clock.nowMillis(), key).thenReturn(CacheRead.exactHit(completion));
                })
                .switchIfEmpty(Mono.defer(() -> commands.zrem(EXACT_LRU, key).then(Mono.empty())));
    }

    private Mono<CacheRead> readSemantic(CacheQuery query, CanonicalRequest.Digests digests) {
        if (!semanticLookup(query)) {
            return Mono.just(CacheRead.miss(List.of(EXACT_MISS), null));
        }
        String text = UserPrompt.finalUserText(query.request());
        if (text == null) {
            return Mono.just(CacheRead.miss(List.of(EXACT_MISS), null));
        }
        return Mono.defer(() -> embeddings.embed(text))
                .flatMap(vector -> {
                    if (vector == null || vector.length != settings.dimensions()) {
                        int length = vector == null ? 0 : vector.length;
                        log.warn("embedding length {} does not match the index dimension {}", length, settings.dimensions());
                        return Mono.just(CacheRead.miss(List.of(EXACT_MISS, SEMANTIC_MISS), null));
                    }
                    return search(query, digests, vector)
                            .defaultIfEmpty(CacheRead.miss(List.of(EXACT_MISS, SEMANTIC_MISS), vector));
                })
                .onErrorResume(error -> {
                    log.warn("semantic cache lookup failed ({})", error.getClass().getSimpleName());
                    return Mono.just(CacheRead.miss(List.of(EXACT_MISS, SEMANTIC_MISS), null));
                });
    }

    private Mono<CacheRead> search(CacheQuery query, CanonicalRequest.Digests digests, float[] vector) {
        return vectors.nearest(query.tenantId(), query.resolvedModel(), digests.contextHash(), vector)
                .flatMap(result -> {
                    double similarity = Cosine.fromDistance(result.distance());
                    if (!Cosine.hits(similarity, query.threshold())) {
                        log.debug("semantic similarity {} is below {}", similarity, query.threshold());
                        return Mono.empty();
                    }
                    CachedCompletion completion = decode(result.payload());
                    if (completion == null) {
                        return commands.del(result.key()).then(Mono.empty());
                    }
                    return commands.zadd(SEMANTIC_LRU, clock.nowMillis(), result.key())
                            .thenReturn(CacheRead.semanticHit(completion));
                });
    }

    private Mono<Void> writeExact(CacheQuery query, CanonicalRequest.Digests digests, CachedCompletion completion) {
        byte[] key = bytes(ExactCacheKey.redisKey(query.tenantId(), digests.exactHash()));
        return commands.set(key, encode(completion), SetArgs.Builder.ex(ttlSeconds(settings.exactTtl())))
                .then(commands.zadd(EXACT_LRU, clock.nowMillis(), key))
                .then(trim(EXACT_LRU, settings.exactMaxEntries()));
    }

    private Mono<Void> writeSemantic(
            CacheQuery query,
            CanonicalRequest.Digests digests,
            CachedCompletion completion,
            float[] embedding
    ) {
        if (!semanticLookup(query) || embedding == null || embedding.length != settings.dimensions()) {
            return Mono.empty();
        }
        if (UserPrompt.finalUserText(query.request()) == null) {
            return Mono.empty();
        }
        byte[] key = bytes(ExactCacheKey.semanticRedisKey(query.tenantId(), digests.exactHash()));
        Map<byte[], byte[]> fields = new LinkedHashMap<>();
        fields.put(bytes("tenant"), bytes(query.tenantId()));
        fields.put(bytes("model"), bytes(query.resolvedModel()));
        fields.put(bytes("context"), bytes(digests.contextHash()));
        fields.put(bytes("payload"), encode(completion));
        fields.put(bytes("embedding"), VectorBytes.toBytes(embedding));
        return commands.hset(key, fields)
                .then(commands.expire(key, ttlSeconds(settings.semanticTtl())))
                .then(commands.zadd(SEMANTIC_LRU, clock.nowMillis(), key))
                .then(trim(SEMANTIC_LRU, settings.semanticMaxEntries()));
    }

    private boolean semanticLookup(CacheQuery query) {
        return query.semantic() && semanticReady.get() && embeddings.available();
    }

    private Mono<Void> trim(byte[] lru, int max) {
        return commands.zcard(lru).flatMap(count -> {
            long extra = count - max;
            if (extra <= 0) {
                return Mono.empty();
            }
            int steps = (int) Math.min(extra, Integer.MAX_VALUE);
            return Flux.range(0, steps)
                    .concatMap(ignored -> commands.zpopmin(lru)
                            .flatMap(scored -> {
                                byte[] member = scored == null ? null : scored.getValue();
                                if (member == null) {
                                    return Mono.empty();
                                }
                                return commands.del(member).then();
                            }))
                    .then();
        });
    }

    private CachedCompletion decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        try {
            CachedCompletion completion = mapper.readValue(payload, CachedCompletion.class);
            if (completion == null || completion.response() == null) {
                return null;
            }
            return completion;
        } catch (IOException ex) {
            log.warn("cache payload could not be read");
            return null;
        }
    }

    private byte[] encode(CachedCompletion completion) {
        try {
            return mapper.writeValueAsBytes(completion);
        } catch (JacksonException ex) {
            throw new IllegalStateException("cache payload could not be written", ex);
        }
    }

    private static long ttlSeconds(Duration ttl) {
        return Math.max(1L, ttl.toSeconds());
    }

    private static boolean alreadyExists(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            String text = cursor.getMessage();
            if (text != null && text.toLowerCase(Locale.ROOT).contains("already exists")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static String message(Throwable error) {
        String text = error.getMessage();
        if (text == null || text.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
