package dev.gautamgupta.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gautamgupta.gateway.api.json.GatewayJson;
import dev.gautamgupta.gateway.core.chat.ChatCompletionService;
import dev.gautamgupta.gateway.core.provider.ProviderClient;
import dev.gautamgupta.gateway.core.provider.ProviderRegistry;
import dev.gautamgupta.gateway.providers.openai.OpenAiProviderClient;
import io.netty.channel.ChannelOption;
import java.util.List;
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
import reactor.netty.http.client.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAiProviderProperties.class)
public class ProviderConfiguration {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer gatewayJacksonCustomizer() {
        return builder -> builder.postConfigurer(GatewayJson::configure);
    }

    @Bean
    CodecCustomizer gatewayCodecCustomizer(OpenAiProviderProperties properties) {
        return configurer -> configurer.defaultCodecs().maxInMemorySize(properties.getMaxInMemoryBytes());
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
    ProviderRegistry providerRegistry(List<ProviderClient> clients) {
        return new ProviderRegistry(clients);
    }

    @Bean
    ChatCompletionService chatCompletionService(ProviderRegistry providerRegistry) {
        return new ChatCompletionService(providerRegistry);
    }
}
