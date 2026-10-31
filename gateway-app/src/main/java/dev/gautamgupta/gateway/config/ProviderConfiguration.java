package dev.gautamgupta.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gautamgupta.gateway.api.json.GatewayJson;
import dev.gautamgupta.gateway.core.chat.ChatCompletionService;
import dev.gautamgupta.gateway.core.embed.EmbeddingService;
import dev.gautamgupta.gateway.core.provider.ProviderClient;
import dev.gautamgupta.gateway.core.provider.ProviderRegistry;
import dev.gautamgupta.gateway.core.routing.ModelRouter;
import dev.gautamgupta.gateway.core.routing.ProviderCallGuard;
import dev.gautamgupta.gateway.core.routing.RoutingPolicy;
import dev.gautamgupta.gateway.providers.anthropic.AnthropicProviderClient;
import dev.gautamgupta.gateway.providers.ollama.OllamaProviderClient;
import dev.gautamgupta.gateway.providers.openai.OpenAiProviderClient;
import io.netty.channel.ChannelOption;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.codec.CodecCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.web.reactive.function.client.WebClient;
import io.micrometer.observation.ObservationRegistry;
import reactor.netty.http.client.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        OpenAiProviderProperties.class,
        AnthropicProviderProperties.class,
        OllamaProviderProperties.class,
        GatewayRoutingProperties.class
})
public class ProviderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ProviderConfiguration.class);

    @Bean
    Jackson2ObjectMapperBuilderCustomizer gatewayJacksonCustomizer() {
        return builder -> builder.postConfigurer(GatewayJson::configure);
    }

    @Bean
    CodecCustomizer gatewayCodecCustomizer(
            OpenAiProviderProperties openAi,
            AnthropicProviderProperties anthropic,
            OllamaProviderProperties ollama
    ) {
        int maxBytes = Math.max(
                openAi.getMaxInMemoryBytes(),
                Math.max(anthropic.getMaxInMemoryBytes(), ollama.getMaxInMemoryBytes()));
        return configurer -> configurer.defaultCodecs().maxInMemorySize(maxBytes);
    }

    @Bean
    OpenAiProviderClient openAiProviderClient(OpenAiProviderProperties properties, ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.getConnectTimeout().toMillis())
                .responseTimeout(properties.getResponseTimeout());
        WebClient webClient = WebClient.builder()
                .baseUrl(properties.normalizedBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("User-Agent", "jvm-llm-gateway/0.1.0")
                .codecs(codecs -> {
                    codecs.defaultCodecs().maxInMemorySize(properties.getMaxInMemoryBytes());
                    codecs.defaultCodecs().jackson2JsonEncoder(
                            new Jackson2JsonEncoder(objectMapper, MediaType.APPLICATION_JSON));
                    codecs.defaultCodecs().jackson2JsonDecoder(
                            new Jackson2JsonDecoder(objectMapper, MediaType.APPLICATION_JSON));
                })
                .build();
        return new OpenAiProviderClient(webClient, properties.toSettings(), objectMapper);
    }

    @Bean
    AnthropicProviderClient anthropicProviderClient(AnthropicProviderProperties properties, ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.getConnectTimeout().toMillis())
                .responseTimeout(properties.getResponseTimeout());
        WebClient webClient = WebClient.builder()
                .baseUrl(properties.normalizedBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("User-Agent", "jvm-llm-gateway/0.1.0")
                .codecs(codecs -> {
                    codecs.defaultCodecs().maxInMemorySize(properties.getMaxInMemoryBytes());
                    codecs.defaultCodecs().jackson2JsonEncoder(
                            new Jackson2JsonEncoder(objectMapper, MediaType.APPLICATION_JSON));
                    codecs.defaultCodecs().jackson2JsonDecoder(
                            new Jackson2JsonDecoder(objectMapper, MediaType.APPLICATION_JSON));
                })
                .build();
        return new AnthropicProviderClient(webClient, properties.toSettings(), objectMapper);
    }

    @Bean
    OllamaProviderClient ollamaProviderClient(OllamaProviderProperties properties, ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.getConnectTimeout().toMillis())
                .responseTimeout(properties.getResponseTimeout());
        WebClient webClient = WebClient.builder()
                .baseUrl(properties.normalizedBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("User-Agent", "jvm-llm-gateway/0.1.0")
                .codecs(codecs -> {
                    codecs.defaultCodecs().maxInMemorySize(properties.getMaxInMemoryBytes());
                    codecs.defaultCodecs().jackson2JsonEncoder(
                            new Jackson2JsonEncoder(objectMapper, MediaType.APPLICATION_JSON));
                    codecs.defaultCodecs().jackson2JsonDecoder(
                            new Jackson2JsonDecoder(objectMapper, MediaType.APPLICATION_JSON));
                })
                .build();
        return new OllamaProviderClient(webClient, properties.toSettings(), objectMapper);
    }

    @Bean
    ProviderRegistry providerRegistry(List<ProviderClient> clients) {
        return new ProviderRegistry(clients);
    }

    @Bean
    RoutingPolicy routingPolicy(GatewayRoutingProperties properties, ProviderRegistry providerRegistry) {
        RoutingPolicy policy = properties.toPolicy();
        policy.validate(providerRegistry);
        return policy;
    }

    @Bean
    ModelRouter modelRouter(RoutingPolicy routingPolicy) {
        return new ModelRouter(routingPolicy);
    }

    @Bean
    ProviderCallGuard providerCallGuard(RoutingPolicy routingPolicy) {
        return new ProviderCallGuard(routingPolicy.circuitBreaker());
    }

    @Bean
    ChatCompletionService chatCompletionService(
            ProviderRegistry providerRegistry,
            ModelRouter modelRouter,
            ProviderCallGuard providerCallGuard,
            ObservationRegistry observations
    ) {
        return new ChatCompletionService(providerRegistry, modelRouter, providerCallGuard, observations);
    }

    @Bean
    EmbeddingService embeddingService(
            ProviderRegistry providerRegistry,
            ModelRouter modelRouter,
            ProviderCallGuard providerCallGuard,
            ObservationRegistry observations
    ) {
        return new EmbeddingService(providerRegistry, modelRouter, providerCallGuard, observations);
    }

    @Bean
    ApplicationRunner gatewayConfigurationCheck(
            OpenAiProviderProperties openAi,
            AnthropicProviderProperties anthropic,
            OllamaProviderProperties ollama,
            CacheProperties cache,
            ProviderRegistry providerRegistry
    ) {
        return args -> {
            List<String> problems = GatewayStartupChecks.problems(openAi, anthropic, ollama, cache, providerRegistry);
            if (!problems.isEmpty()) {
                throw new IllegalStateException("Invalid gateway configuration: " + String.join("; ", problems));
            }
            if (cache.isEnabled()
                    && cache.getSemantic().isEnabled()
                    && !GatewayStartupChecks.semanticProviderReady(
                            providerRegistry, cache.getSemantic().getEmbeddingProvider())) {
                log.warn(
                        "semantic cache embedding provider {} is not configured; semantic lookups will miss",
                        cache.getSemantic().getEmbeddingProvider());
            }
        };
    }
}
