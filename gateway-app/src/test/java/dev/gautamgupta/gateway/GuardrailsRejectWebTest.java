package dev.gautamgupta.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                OfflineDataStores.EXCLUDE,
                OfflineDataStores.CACHE_OFF,
                "gateway.guardrails.mode=reject",
                "management.otlp.tracing.export.enabled=false"
        })
class GuardrailsRejectWebTest {

    private static final WireMockServer WIRE_MOCK = start();

    @Autowired
    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.openai.base-url", () -> "http://127.0.0.1:" + WIRE_MOCK.port());
        registry.add("gateway.providers.openai.api-key", () -> "sk-test");
        registry.add("gateway.providers.anthropic.base-url", () -> "http://127.0.0.1:" + WIRE_MOCK.port());
        registry.add("gateway.providers.anthropic.api-key", () -> "sk-ant-test");
        registry.add("management.server.port", () -> "0");
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @BeforeEach
    void reset() {
        WIRE_MOCK.resetAll();
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"chatcmpl-test","object":"chat.completion","created":1,"model":"gpt-4o-mini",
                                 "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                                """)));
        webTestClient = webTestClient.mutate().responseTimeout(Duration.ofSeconds(10)).build();
    }

    @Test
    void rejectModeReturns400AndDoesNotCallTheProvider() {
        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"gpt-4o-mini","messages":[{"role":"user","content":"Email ada@example.com"}]}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("pii_detected")
                .jsonPath("$.error.message").value(message -> assertThat(message.toString()).doesNotContain("ada@example.com"));
        assertThat(WIRE_MOCK.getAllServeEvents()).isEmpty();
    }

    @Test
    void aCleanPromptIsStillForwarded() {
        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello"}]}
                        """)
                .exchange()
                .expectStatus().isOk();
        assertThat(WIRE_MOCK.getAllServeEvents()).hasSize(1);
    }

    private static WireMockServer start() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }
}
