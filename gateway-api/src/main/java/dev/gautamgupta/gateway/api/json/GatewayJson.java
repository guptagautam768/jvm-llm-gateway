package dev.gautamgupta.gateway.api.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * ObjectMapper settings shared by the gateway and its tests.
 * OpenAI wire fields are snake_case. Unknown properties are ignored (FR-001).
 */
public final class GatewayJson {

    private GatewayJson() {
    }

    public static ObjectMapper create() {
        ObjectMapper mapper = JsonMapper.builder().build();
        configure(mapper);
        return mapper;
    }

    public static void configure(ObjectMapper mapper) {
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.setDefaultPropertyInclusion(JsonInclude.Value.construct(
                JsonInclude.Include.NON_NULL,
                JsonInclude.Include.NON_NULL));
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }
}
