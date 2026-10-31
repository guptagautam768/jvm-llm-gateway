package dev.gautamgupta.gateway.config;

import dev.gautamgupta.gateway.web.RequestIdThreadLocalAccessor;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CorrelationConfiguration {

    public CorrelationConfiguration() {
        RequestIdThreadLocalAccessor.register();
    }
}
