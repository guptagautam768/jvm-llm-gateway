package dev.gautamgupta.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * AC-010: redacted text is what WireMock receives, for a normal response and a stream.
 * Logs from the gateway logger must not contain the prompt.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                OfflineDataStores.EXCLUDE,
                OfflineDataStores.CACHE_OFF,
                "gateway.guardrails.mode=redact",
                "gateway.guardrails.max-request-bytes=64",
                "management.tracing.sampling.probability=1.0",
                "management.otlp.tracing.export.enabled=false"
        })
class GuardrailsWebTest {

    private static final WireMockServer WIRE_MOCK = start();
    private static final String SECRET = "pii-secret-z9k2@example.com";
    private static final String SENTINEL = "PROMPT_SENTINEL_z9k2";

    @Autowired
    private WebTestClient webTestClient;

    @LocalManagementPort
    private int managementPort;

    private WebTestClient managementClient;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.openai.base-url", () -> "http://127.0.0.1:" + WIRE_MOCK.port());
        registry.add("gateway.providers.openai.api-key", () -> "sk-test");
        registry.add("gateway.providers.anthropic.base-url", () -> "http://127.0.0.1:" + WIRE_MOCK.port());
        registry.add("gateway.providers.anthropic.api-key", () -> "sk-ant-test");
        registry.add("management.server.port", () -> "0");
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
    void redactedTextReachesTheProviderAndTheMetric() {
        stubChat();
        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "req-pii-1")
                .bodyValue("""
                        {"model":"gpt-4o-mini","messages":[{"role":"user","content":"Email %s about %s"}]}
                        """.formatted(SECRET, SENTINEL))
                .exchange()
                .expectStatus().isOk();

        String upstream = WIRE_MOCK.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(upstream).contains("[EMAIL_1]").contains(SENTINEL).doesNotContain(SECRET);

        String scrape = prometheus();
        assertThat(scrape).contains("gateway_pii_redactions_total");
        assertThat(scrape).contains("type=\"email\"");
        assertThat(scrape).contains("gateway_requests_total");
    }

    @Test
    void streamingRedactsThePromptBeforeTheProvider() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("""
                                data: {"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":"ok"}}]}

                                data: [DONE]

                                """)));

        String body = webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("""
                        {"model":"gpt-4o-mini","stream":true,"messages":[{"role":"user","content":"ping %s"}]}
                        """.formatted(SECRET))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .map(parts -> String.join("", parts))
                .block(Duration.ofSeconds(10));

        assertThat(body).contains("[DONE]");
        String upstream = WIRE_MOCK.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(upstream).contains("[EMAIL_1]").contains("\"stream\":true").doesNotContain(SECRET);
    }

    @Test
    void oversizedMessageIs413() {
        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"gpt-4o-mini","messages":[{"role":"user","content":"%s"}]}
                        """.formatted("x".repeat(80)))
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("request_too_large");
        assertThat(WIRE_MOCK.getAllServeEvents()).isEmpty();
    }

    @Test
    void gatewayLogsDoNotContainThePrompt() {
        stubChat();
        Logger logger = (Logger) LoggerFactory.getLogger("dev.gautamgupta.gateway");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            webTestClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Request-Id", "req-log-1")
                    .bodyValue("""
                            {"model":"gpt-4o-mini","messages":[{"role":"user","content":"Email %s about %s"}]}
                            """.formatted(SECRET, SENTINEL))
                    .exchange()
                    .expectStatus().isOk();
        } finally {
            logger.detachAppender(appender);
        }

        String logs = appender.list.stream()
                .map(event -> event.getFormattedMessage() + " " + event.getMDCPropertyMap())
                .collect(Collectors.joining("\n"));
        assertThat(logs).doesNotContain(SECRET).doesNotContain(SENTINEL);
        assertThat(logs).contains("requestId=req-log-1");
        assertThat(logs).contains("email=1");
        assertThat(logs).doesNotContain("traceId=none");
        assertThat(logs).contains("traceId=");
    }

    private String prometheus() {
        return managementClient.get()
                .uri("/actuator/prometheus")
                .header("Accept", "text/plain;version=0.0.4;charset=utf-8,*/*;q=0.1")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private static void stubChat() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"chatcmpl-test","object":"chat.completion","created":1,"model":"gpt-4o-mini",
                                 "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                                 "usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3}}
                                """)));
    }

    private static WireMockServer start() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }
}
