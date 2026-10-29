package dev.gautamgupta.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
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
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatPassthroughTest {

    private static final WireMockServer WIRE_MOCK = start();

    @Autowired
    private WebTestClient webTestClient;

    @LocalManagementPort
    private int managementPort;

    private WebTestClient managementClient;

    private static WireMockServer start() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.openai.base-url", () -> "http://127.0.0.1:" + WIRE_MOCK.port());
        registry.add("gateway.providers.openai.api-key", () -> "sk-test");
        registry.add("management.server.port", () -> "0");
        // Boot's test observability customizer turns export off. Re-enable Prometheus for this test.
        registry.add("management.prometheus.metrics.export.enabled", () -> "true");
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @BeforeEach
    void reset() {
        WIRE_MOCK.resetAll();
        webTestClient = webTestClient.mutate().responseTimeout(Duration.ofSeconds(10)).build();
        managementClient = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + managementPort)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    void nonStreamingChatIsPassedThrough() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "chatcmpl-test",
                                  "object": "chat.completion",
                                  "created": 1700000000,
                                  "model": "gpt-4o-mini",
                                  "choices": [{
                                    "index": 0,
                                    "message": {"role": "assistant", "content": "Hello from upstream"},
                                    "finish_reason": "stop"
                                  }],
                                  "usage": {"prompt_tokens": 5, "completion_tokens": 7, "total_tokens": 12}
                                }
                                """)));

        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "req-123")
                .bodyValue("""
                        {"model":"gpt-4o-mini","temperature":0.2,"max_tokens":16,"messages":[{"role":"user","content":"Hello"}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals("X-Request-Id", "req-123")
                .expectBody()
                .jsonPath("$.id").isEqualTo("chatcmpl-test")
                .jsonPath("$.object").isEqualTo("chat.completion")
                .jsonPath("$.choices[0].message.role").isEqualTo("assistant")
                .jsonPath("$.choices[0].message.content").isEqualTo("Hello from upstream")
                .jsonPath("$.choices[0].finish_reason").isEqualTo("stop")
                .jsonPath("$.usage.total_tokens").isEqualTo(12);

        var request = WIRE_MOCK.getAllServeEvents().get(0).getRequest();
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer sk-test");
        assertThat(request.getBodyAsString()).contains("\"model\":\"gpt-4o-mini\"").doesNotContain("\"stream\"");
    }

    @Test
    void streamingChatIsPassedThroughAsSse() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("""
                                data: {"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"}}]}

                                data: {"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                                data: [DONE]

                                """)));

        String body = webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("""
                        {"model":"gpt-4o-mini","stream":true,"messages":[{"role":"user","content":"Hello"}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .map(parts -> String.join("", parts))
                .block(Duration.ofSeconds(10));

        assertThat(body).contains("chat.completion.chunk").contains("Hello").contains("[DONE]");
        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer sk-test"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.stream", equalTo("true")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.messages[0].content", equalTo("Hello"))));
    }

    @Test
    void modelsListIsPassedThrough() {
        WIRE_MOCK.stubFor(get(urlEqualTo("/v1/models"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"object":"list","data":[{"id":"gpt-4o-mini","object":"model","created":1,"owned_by":"openai"}]}
                                """)));

        webTestClient.get()
                .uri("/v1/models")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data[0].id").isEqualTo("gpt-4o-mini")
                .jsonPath("$.data[0].owned_by").isEqualTo("openai");

        WIRE_MOCK.verify(getRequestedFor(urlEqualTo("/v1/models"))
                .withHeader("Authorization", equalTo("Bearer sk-test")));
    }

    @Test
    void upstreamErrorBodyIsPreserved() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Retry-After", "3")
                        .withBody("""
                                {"error":{"message":"Incorrect API key","type":"invalid_request_error","code":"invalid_api_key"}}
                                """)));

        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello"}]}
                        """)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("Retry-After", "3")
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_api_key")
                .jsonPath("$.error.type").isEqualTo("invalid_request_error");
    }

    @Test
    void missingModelIs400() {
        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"messages":[{"role":"user","content":"Hello"}]}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_request")
                .jsonPath("$.error.param").isEqualTo("model");
        assertThat(WIRE_MOCK.getAllServeEvents()).isEmpty();
    }

    @Test
    void malformedJsonIs400() {
        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_json");
    }

    @Test
    void actuatorIsOnlyOnTheManagementPort() {
        webTestClient.get().uri("/actuator/health").exchange().expectStatus().isNotFound();
        webTestClient.get().uri("/actuator/prometheus").exchange().expectStatus().isNotFound();

        managementClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");

        managementClient.get()
                .uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk();

        managementClient.get()
                .uri("/actuator/info")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.modules").isArray();
    }

    @Test
    void prometheusSeesTheChatRequest() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "chatcmpl-test",
                                  "object": "chat.completion",
                                  "created": 1,
                                  "model": "gpt-4o-mini",
                                  "choices": [{"index": 0, "message": {"role": "assistant", "content": "ok"}, "finish_reason": "stop"}],
                                  "usage": {"prompt_tokens": 2, "completion_tokens": 1, "total_tokens": 3}
                                }
                                """)));

        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hi"}]}
                        """)
                .exchange()
                .expectStatus().isOk();

        String scrape = managementClient.get()
                .uri("/actuator/prometheus")
                .header("Accept", "text/plain;version=0.0.4;charset=utf-8,*/*;q=0.1")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(scrape).contains("gateway_requests_total");
        assertThat(scrape).contains("gateway_provider_latency");
        assertThat(scrape).contains("gateway_tokens_total");
    }
}
