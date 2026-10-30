package dev.gautamgupta.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.gautamgupta.gateway.core.routing.ProviderCallGuard;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class RoutingResilienceTest {

    private static final WireMockServer WIRE_MOCK = start();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ProviderCallGuard providerCallGuard;

    private static WireMockServer start() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.openai.base-url", () -> "http://127.0.0.1:" + WIRE_MOCK.port());
        registry.add("gateway.providers.openai.api-key", () -> "sk-test");
        registry.add("gateway.providers.openai.response-timeout", () -> "5s");
        registry.add("gateway.providers.anthropic.base-url", () -> "http://127.0.0.1:" + WIRE_MOCK.port());
        registry.add("gateway.providers.anthropic.api-key", () -> "sk-ant-test");
        registry.add("gateway.providers.anthropic.response-timeout", () -> "5s");
        registry.add("gateway.routes.fast.timeout", () -> "400ms");
        registry.add("gateway.routes.fast.retry.max-attempts", () -> "1");
        registry.add("gateway.routes.fast.retry.backoff", () -> "1ms");
        registry.add("gateway.routes.fast.retry.multiplier", () -> "1");
        registry.add("gateway.resilience.circuit-breaker.sliding-window-size", () -> "2");
        registry.add("gateway.resilience.circuit-breaker.minimum-number-of-calls", () -> "2");
        registry.add("gateway.resilience.circuit-breaker.failure-rate-threshold", () -> "50");
        registry.add("gateway.resilience.circuit-breaker.wait-duration-in-open-state", () -> "60s");
        registry.add("gateway.resilience.circuit-breaker.permitted-number-of-calls-in-half-open-state", () -> "1");
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @BeforeEach
    void reset() {
        WIRE_MOCK.resetAll();
        providerCallGuard.circuitBreakers().getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        webTestClient = webTestClient.mutate().responseTimeout(Duration.ofSeconds(10)).build();
    }

    @Test
    void aliasFallsBackWhenThePrimaryReturns500() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":{\"message\":\"down\"}}")));
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "msg_fallback",
                                  "type": "message",
                                  "role": "assistant",
                                  "model": "claude-haiku-4-5",
                                  "content": [{"type": "text", "text": "from anthropic"}],
                                  "stop_reason": "end_turn",
                                  "usage": {"input_tokens": 2, "output_tokens": 2}
                                }
                                """)));

        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"fast","messages":[{"role":"user","content":"Hello"}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.model").isEqualTo("fast")
                .jsonPath("$.object").isEqualTo("chat.completion")
                .jsonPath("$.choices[0].message.content").isEqualTo("from anthropic")
                .jsonPath("$.choices[0].finish_reason").isEqualTo("stop");

        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("gpt-4o-mini"))));
        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withHeader("x-api-key", equalTo("sk-ant-test"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("claude-haiku-4-5")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("Hello"))));
    }

    @Test
    void aliasFallsBackWhenThePrimaryTimesOut() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(2_000)
                        .withBody("{\"id\":\"late\"}")));
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "msg_timeout",
                                  "type": "message",
                                  "role": "assistant",
                                  "model": "claude-haiku-4-5",
                                  "content": [{"type": "text", "text": "after timeout"}],
                                  "stop_reason": "max_tokens",
                                  "usage": {"input_tokens": 1, "output_tokens": 1}
                                }
                                """)));

        long started = System.nanoTime();
        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"fast","messages":[{"role":"user","content":"Hello"}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("after timeout")
                .jsonPath("$.choices[0].finish_reason").isEqualTo("length")
                .jsonPath("$.model").isEqualTo("fast");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        WIRE_MOCK.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void circuitOpensAndLaterCallsSkipThePrimary() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":{\"message\":\"down\"}}")));
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "msg_cb",
                                  "type": "message",
                                  "role": "assistant",
                                  "model": "claude-haiku-4-5",
                                  "content": [{"type": "text", "text": "fallback"}],
                                  "stop_reason": "end_turn"
                                }
                                """)));

        for (int i = 0; i < 3; i++) {
            webTestClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"model":"fast","messages":[{"role":"user","content":"Hello"}]}
                            """)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.choices[0].message.content").isEqualTo("fallback");
        }

        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/v1/chat/completions")))).hasSize(2);
        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/v1/messages")))).hasSize(3);
        assertThat(providerCallGuard.circuitBreakers().circuitBreaker("openai:gpt-4o-mini").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void clientErrorDoesNotFallBack() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"error":{"message":"bad model","type":"invalid_request_error","code":"model_not_found"}}
                                """)));

        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"fast","messages":[{"role":"user","content":"Hello"}]}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("model_not_found");

        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlEqualTo("/v1/messages")))).isEmpty();
    }
}
