package dev.gautamgupta.gateway.config;

import dev.gautamgupta.gateway.guardrails.PromptRedactor;
import dev.gautamgupta.gateway.guardrails.RequestSizeLimit;
import dev.gautamgupta.gateway.guardrails.pii.ConfiguredRequestSizeLimit;
import dev.gautamgupta.gateway.guardrails.pii.PiiRedactionPipeline;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** ADR-0009 pipeline. Size limit runs first, then redact-and-forward. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GuardrailsProperties.class)
public class GuardrailsConfiguration {

    @Bean
    RequestSizeLimit requestSizeLimit(GuardrailsProperties properties) {
        return new ConfiguredRequestSizeLimit(properties.getMaxRequestBytes());
    }

    @Bean
    PromptRedactor promptRedactor(GuardrailsProperties properties) {
        return PiiRedactionPipeline.create(properties.toSettings());
    }
}
