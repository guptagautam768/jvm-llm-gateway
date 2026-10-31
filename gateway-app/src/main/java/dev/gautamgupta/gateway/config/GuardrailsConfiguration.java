package dev.gautamgupta.gateway.config;

import dev.gautamgupta.gateway.guardrails.NoopPromptRedactor;
import dev.gautamgupta.gateway.guardrails.NoopRequestSizeLimit;
import dev.gautamgupta.gateway.guardrails.PromptRedactor;
import dev.gautamgupta.gateway.guardrails.RequestSizeLimit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Week 4 hooks. Week 5 replaces both beans (ADR-0009). */
@Configuration(proxyBeanMethods = false)
public class GuardrailsConfiguration {

    @Bean
    RequestSizeLimit requestSizeLimit() {
        return new NoopRequestSizeLimit();
    }

    @Bean
    PromptRedactor promptRedactor() {
        return new NoopPromptRedactor();
    }
}
