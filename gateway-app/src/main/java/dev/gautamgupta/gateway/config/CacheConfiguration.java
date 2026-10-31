package dev.gautamgupta.gateway.config;

import dev.gautamgupta.gateway.cache.CacheClock;
import dev.gautamgupta.gateway.cache.EmbeddingClient;
import dev.gautamgupta.gateway.cache.NoopResponseCache;
import dev.gautamgupta.gateway.cache.RedisResponseCache;
import dev.gautamgupta.gateway.cache.RedisStackClient;
import dev.gautamgupta.gateway.cache.ResponseCache;
import dev.gautamgupta.gateway.core.routing.ModelRouter;
import dev.gautamgupta.gateway.guardrails.PromptRedactor;
import dev.gautamgupta.gateway.guardrails.RequestSizeLimit;
import dev.gautamgupta.gateway.providers.openai.OpenAiProviderClient;
import dev.gautamgupta.gateway.metrics.GatewayMetrics;
import dev.gautamgupta.gateway.web.ChatCacheService;
import io.micrometer.observation.ObservationRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CacheConfiguration.class);

    @Bean
    @ConditionalOnProperty(prefix = "gateway.cache", name = "enabled", havingValue = "false")
    ResponseCache noopResponseCache() {
        return new NoopResponseCache();
    }

    @Bean
    ChatCacheService chatCacheService(
            CacheProperties properties,
            ModelRouter router,
            ResponseCache cache,
            PromptRedactor redactor,
            RequestSizeLimit sizeLimit,
            GatewayMetrics metrics,
            ObservationRegistry observations
    ) {
        return new ChatCacheService(properties, router, cache, redactor, sizeLimit, metrics, observations);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "gateway.cache", name = "enabled", havingValue = "true")
    static class EnabledCacheConfiguration {

        @Bean(destroyMethod = "close")
        RedisStackClient redisStackClient(RedisProperties properties) {
            return RedisStackClient.connect(
                    properties.getHost(),
                    properties.getPort(),
                    properties.getUsername(),
                    properties.getPassword(),
                    properties.getDatabase());
        }

        @Bean
        EmbeddingClient embeddingClient(
                OpenAiProviderClient openAi,
                ObjectMapper objectMapper,
                CacheProperties properties
        ) {
            CacheProperties.Semantic semantic = properties.getSemantic();
            return new OpenAiEmbeddingClient(
                    openAi,
                    objectMapper,
                    semantic.getEmbeddingProvider(),
                    semantic.getEmbeddingModel(),
                    semantic.getDimensions());
        }

        @Bean
        ResponseCache responseCache(RedisStackClient redis, EmbeddingClient embeddings, CacheProperties properties) {
            CacheClock clock = System::currentTimeMillis;
            return new RedisResponseCache(redis, embeddings, properties.toSettings(), clock);
        }

        @Bean
        ApplicationRunner semanticCacheIndex(ResponseCache cache) {
            return args -> {
                if (!(cache instanceof RedisResponseCache redis)) {
                    return;
                }
                try {
                    redis.ensureIndex().block(Duration.ofSeconds(15));
                } catch (RuntimeException ex) {
                    log.error("semantic cache index setup failed");
                    redis.disableSemantic();
                }
            };
        }
    }
}
