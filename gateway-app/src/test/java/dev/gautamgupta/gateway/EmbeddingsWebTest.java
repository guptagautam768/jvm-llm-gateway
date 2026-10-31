package dev.gautamgupta.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.embeddings.EmbeddingCreateParams;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                OfflineDataStores.EXCLUDE,
                OfflineDataStores.CACHE_OFF,
                "management.server.port=0",
                "gateway.routes.local.targets[0].provider=ollama",
                "gateway.routes.local.targets[0].model=llama3.2"
        }
)
class EmbeddingsWebTest {

    private static final WireMockServer WIRE_MOCK = start();

    @Autowired
    private WebTestClient webTestClient;

    @LocalServerPort
    private int port;

    private static WireMockServer start() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.openai.base-url", () -> "http://127.0.0.1:" + WIRE_MOCK.port());
        registry.add("gateway.providers.openai.api-key", () -> "sk-test");
        registry.add("gateway.providers.anthropic.base-url", () -> "http://127.0.0.1:" + WIRE_MOCK.port());
        registry.add("gateway.providers.anthropic.api-key", () -> "sk-ant-test");
        registry.add("gateway.providers.ollama.base-url", () -> "http://127.0.0.1:" + WIRE_MOCK.port());
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @BeforeEach
    void reset() {
        WIRE_MOCK.resetAll();
        webTestClient = webTestClient.mutate().responseTimeout(Duration.ofSeconds(10)).build();
    }

    @Test
    void embeddingsAliasIsOpenAiShaped() {
        stubOpenAiEmbedding();

        webTestClient.post()
                .uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"embed","input":"hello"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.model").isEqualTo("embed")
                .jsonPath("$.data[0].object").isEqualTo("embedding")
                .jsonPath("$.data[0].index").isEqualTo(0)
                .jsonPath("$.data[0].embedding[0]").isEqualTo(0.1)
                .jsonPath("$.usage.prompt_tokens").isEqualTo(3);

        String body = WIRE_MOCK.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(WIRE_MOCK.getAllServeEvents().get(0).getRequest().getUrl()).isEqualTo("/v1/embeddings");
        assertThat(body).contains("\"model\":\"text-embedding-3-small\"").contains("\"input\":\"hello\"");
    }

    @Test
    void embeddingsArrayAndPiiAreRedactedBeforeOpenAi() {
        stubOpenAiEmbedding();

        webTestClient.post()
                .uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"text-embedding-3-small","input":["mail ada@example.com","second"]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.model").isEqualTo("text-embedding-3-small");

        String body = WIRE_MOCK.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(body).contains("[EMAIL_1]").doesNotContain("ada@example.com");
        assertThat(body).contains("second");
    }

    @Test
    void openaiEmbeddingFailureFallsBackToOllama() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/embeddings"))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":{\"message\":\"down\"}}")));
        WIRE_MOCK.stubFor(post(urlEqualTo("/api/embed"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"model":"nomic-embed-text","embeddings":[[0.4,0.5]],"prompt_eval_count":2}
                                """)));

        webTestClient.post()
                .uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"embed","input":"hello"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.model").isEqualTo("embed")
                .jsonPath("$.data[0].embedding[0]").isEqualTo(0.4);

        assertThat(WIRE_MOCK.getAllServeEvents())
                .extracting(event -> event.getRequest().getUrl())
                .contains("/v1/embeddings", "/api/embed");
    }

    @Test
    void anthropicOnlyModelCannotEmbed() {
        webTestClient.post()
                .uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"claude-haiku-4-5","input":"hello"}
                        """)
                .exchange()
                .expectStatus().isEqualTo(501)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("not_implemented")
                .jsonPath("$.error.type").isEqualTo("invalid_request_error");

        assertThat(WIRE_MOCK.getAllServeEvents()).isEmpty();
    }

    @Test
    void missingInputIsAnOpenAiError() {
        webTestClient.post()
                .uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"embed"}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_request")
                .jsonPath("$.error.param").isEqualTo("input");
    }

    @Test
    void unknownPathIsAnOpenAiError() {
        webTestClient.get()
                .uri("/v1/does-not-exist")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("not_found")
                .jsonPath("$.error.type").isEqualTo("invalid_request_error");
    }

    @Test
    void ollamaChatAndStreamUseTheNativeApi() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/api/chat"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "model": "llama3.2",
                                  "created_at": "2026-01-01T00:00:00Z",
                                  "message": {"role": "assistant", "content": "local hello"},
                                  "done": true,
                                  "done_reason": "stop",
                                  "prompt_eval_count": 2,
                                  "eval_count": 2
                                }
                                """)));

        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"local","messages":[{"role":"user","content":"Hi"}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.model").isEqualTo("local")
                .jsonPath("$.choices[0].message.content").isEqualTo("local hello")
                .jsonPath("$.usage.total_tokens").isEqualTo(4);

        WIRE_MOCK.resetAll();
        WIRE_MOCK.stubFor(post(urlEqualTo("/api/chat"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody("""
                                {"model":"llama3.2","created_at":"2026-01-01T00:00:00Z","message":{"role":"assistant","content":"Hi"},"done":false}
                                {"model":"llama3.2","created_at":"2026-01-01T00:00:00Z","message":{"role":"assistant","content":""},"done":true,"done_reason":"length","prompt_eval_count":1,"eval_count":1}
                                """)));

        String body = webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("""
                        {"model":"local","stream":true,"messages":[{"role":"user","content":"Hi"}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .map(parts -> String.join("", parts))
                .block(Duration.ofSeconds(10));

        assertThat(body).contains("chat.completion.chunk").contains("Hi").contains("length").contains("[DONE]");
    }

    @Test
    void officialJavaSdkCompletesChatAndEmbeddings() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "chatcmpl-sdk",
                                  "object": "chat.completion",
                                  "created": 1700000000,
                                  "model": "gpt-4o-mini",
                                  "choices": [{
                                    "index": 0,
                                    "message": {"role": "assistant", "content": "Hello from upstream"},
                                    "finish_reason": "stop"
                                  }],
                                  "usage": {"prompt_tokens": 5, "completion_tokens": 4, "total_tokens": 9}
                                }
                                """)));
        stubOpenAiEmbedding();

        OpenAIClient sdk = OpenAIOkHttpClient.builder()
                .apiKey("sk-sdk")
                .baseUrl("http://127.0.0.1:" + port + "/v1")
                .maxRetries(0)
                .build();
        var chat = sdk.chat().completions().create(ChatCompletionCreateParams.builder()
                .model("gpt-4o-mini")
                .addUserMessage("Hello")
                .maxCompletionTokens(16)
                .build());
        assertThat(chat.id()).isEqualTo("chatcmpl-sdk");
        assertThat(chat.choices().get(0).message().content()).contains("Hello from upstream");

        var embeddings = sdk.embeddings().create(EmbeddingCreateParams.builder()
                .model("text-embedding-3-small")
                .input("hello")
                .build());
        assertThat(embeddings.model()).isEqualTo("text-embedding-3-small");
        assertThat(embeddings.data()).isNotEmpty();
        assertThat(embeddings.data().get(0).embedding()).isNotEmpty();
        assertThat(embeddings.data().get(0).embedding().get(0).doubleValue()).isBetween(0.09d, 0.11d);
    }

    private static void stubOpenAiEmbedding() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/embeddings"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1,0.2]}],"model":"text-embedding-3-small","usage":{"prompt_tokens":3,"total_tokens":3}}
                                """)));
    }
}
