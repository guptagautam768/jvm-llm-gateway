package dev.gautamgupta.gateway;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0"
)
class MissingApiKeyTest {

    @Autowired
    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.openai.api-key", () -> "");
        registry.add("gateway.providers.openai.base-url", () -> "http://127.0.0.1:9");
        registry.add("gateway.providers.anthropic.api-key", () -> "");
        registry.add("gateway.providers.anthropic.base-url", () -> "http://127.0.0.1:9");
    }

    @Test
    void chatWithoutAProviderKeyReturns503() {
        webTestClient.mutate().responseTimeout(Duration.ofSeconds(10)).build()
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello"}]}
                        """)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("provider_not_configured")
                .jsonPath("$.error.type").isEqualTo("server_error");
    }
}
