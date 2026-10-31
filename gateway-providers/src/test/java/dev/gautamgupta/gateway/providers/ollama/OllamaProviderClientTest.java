package dev.gautamgupta.gateway.providers.ollama;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.api.embed.EmbeddingRequest;
import dev.gautamgupta.gateway.api.json.GatewayJson;
import dev.gautamgupta.gateway.core.provider.InvalidRequestException;
import dev.gautamgupta.gateway.core.provider.ProviderException;
import dev.gautamgupta.gateway.core.provider.ProviderIds;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

class OllamaProviderClientTest {

    private static final WireMockServer WIRE_MOCK = start();
    private static final ObjectMapper MAPPER = GatewayJson.create();

    private OllamaProviderClient client;

    private static WireMockServer start() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @BeforeEach
    void setUp() {
        WIRE_MOCK.resetAll();
        client = client("http://127.0.0.1:" + WIRE_MOCK.port(), "");
    }

    @Test
    void translatesANonStreamingChatCompletion() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/api/chat"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "model": "llama3.2",
                                  "created_at": "2026-01-01T00:00:00Z",
                                  "message": {"role": "assistant", "content": "Hello from Ollama"},
                                  "done": true,
                                  "done_reason": "stop",
                                  "prompt_eval_count": 5,
                                  "eval_count": 4
                                }
                                """)));

        StepVerifier.create(client.complete(request(false)))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo("chatcmpl-ollama");
                    assertThat(response.object()).isEqualTo("chat.completion");
                    assertThat(response.choices().get(0).message().content().asText()).isEqualTo("Hello from Ollama");
                    assertThat(response.choices().get(0).finishReason()).isEqualTo("stop");
                    assertThat(response.usage().promptTokens()).isEqualTo(5);
                    assertThat(response.usage().completionTokens()).isEqualTo(4);
                    assertThat(response.usage().totalTokens()).isEqualTo(9);
                })
                .verifyComplete();

        String body = WIRE_MOCK.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(body).contains("\"model\":\"llama3.2\"");
        assertThat(body).contains("\"stream\":false");
        assertThat(body).contains("\"temperature\":0.2");
        assertThat(body).contains("\"num_predict\":16");
        assertThat(WIRE_MOCK.getAllServeEvents().get(0).getRequest().getHeader("Authorization")).isNull();
    }

    @Test
    void translatesNdjsonStreamingIntoChunks() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/api/chat"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody("""
                                {"model":"llama3.2","created_at":"2026-01-01T00:00:00Z","message":{"role":"assistant","content":"He"},"done":false}
                                {"model":"llama3.2","created_at":"2026-01-01T00:00:00Z","message":{"role":"assistant","content":"llo"},"done":true,"done_reason":"stop","prompt_eval_count":3,"eval_count":2}
                                """)));

        StepVerifier.create(client.stream(request(true)))
                .assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content().asText()).isEqualTo("He"))
                .assertNext(chunk -> {
                    assertThat(chunk.choices().get(0).delta().content().asText()).isEqualTo("llo");
                    assertThat(chunk.choices().get(0).finishReason()).isEqualTo("stop");
                    assertThat(chunk.usage()).isNull();
                })
                .verifyComplete();

        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/api/chat"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.stream", equalTo("true"))));
    }

    @Test
    void embedsAStringAndAnArray() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/api/embed"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"model":"nomic-embed-text","embeddings":[[0.1,0.2],[0.3,0.4]],"prompt_eval_count":6}
                                """)));

        StepVerifier.create(client.embed(new EmbeddingRequest(
                        "nomic-embed-text",
                        MAPPER.getNodeFactory().arrayNode().add("a").add("b"),
                        8)))
                .assertNext(response -> {
                    assertThat(response.object()).isEqualTo("list");
                    assertThat(response.data()).hasSize(2);
                    assertThat(response.data().get(0).get("object").asText()).isEqualTo("embedding");
                    assertThat(response.data().get(0).get("index").asInt()).isZero();
                    assertThat(response.data().get(1).get("embedding").get(0).asDouble()).isEqualTo(0.3d);
                    assertThat(response.usage().promptTokens()).isEqualTo(6);
                    assertThat(response.usage().totalTokens()).isEqualTo(6);
                })
                .verifyComplete();

        String body = WIRE_MOCK.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(body).contains("\"input\":[\"a\",\"b\"]");
        assertThat(body).contains("\"dimensions\":8");
    }

    @Test
    void rewritesOllamaErrorsIntoTheOpenAiShape() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/api/chat"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"model 'missing' not found\"}")));

        StepVerifier.create(client.complete(request(false)))
                .expectErrorSatisfies(error -> {
                    ProviderException providerException = (ProviderException) error;
                    assertThat(providerException.status()).isEqualTo(404);
                    assertThat(providerException.responseBody()).contains("model_not_found");
                    assertThat(providerException.responseBody()).contains("model 'missing' not found");
                    assertThat(providerException.responseBody()).contains("\"error\"");
                })
                .verify();
    }

    @Test
    void rejectsMoreThanOneCompletionBeforeCallingOllama() {
        ChatCompletionRequest many = new ChatCompletionRequest(
                "llama3.2",
                List.of(new ChatCompletionRequest.ChatMessage("user", TextNode.valueOf("Hello"), null, null, null)),
                null, null, 2, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> client.complete(many))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(WIRE_MOCK.getAllServeEvents()).isEmpty();
    }

    @Test
    void blankBaseUrlDoesNotCallTheNetwork() {
        OllamaProviderClient unconfigured = client("", "");
        assertThatThrownBy(unconfigured::ensureConfigured)
                .isInstanceOf(ProviderException.class)
                .extracting("kind")
                .isEqualTo(ProviderException.Kind.NOT_CONFIGURED);
        assertThat(unconfigured.capabilities().embeddings()).isTrue();
        assertThat(unconfigured.id()).isEqualTo(ProviderIds.OLLAMA);
        assertThat(WIRE_MOCK.getAllServeEvents()).isEmpty();
    }

    @Test
    void optionalApiKeyIsSentAsBearer() {
        OllamaProviderClient keyed = client("http://127.0.0.1:" + WIRE_MOCK.port(), "secret");
        WIRE_MOCK.stubFor(post(urlEqualTo("/api/embed"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"model\":\"nomic-embed-text\",\"embeddings\":[[0.1]],\"prompt_eval_count\":1}")));

        StepVerifier.create(keyed.embed(new EmbeddingRequest("nomic-embed-text", TextNode.valueOf("hi"))))
                .expectNextCount(1)
                .verifyComplete();

        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/api/embed"))
                .withHeader("Authorization", equalTo("Bearer secret")));
    }

    private static ChatCompletionRequest request(boolean stream) {
        return new ChatCompletionRequest(
                "llama3.2",
                List.of(new ChatCompletionRequest.ChatMessage("user", TextNode.valueOf("Hello"), null, null, null)),
                0.2,
                null,
                null,
                stream,
                null,
                null,
                16,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static OllamaProviderClient client(String baseUrl, String apiKey) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
                .responseTimeout(Duration.ofSeconds(2));
        WebClient webClient = WebClient.builder()
                .baseUrl(normalized.isBlank() ? "http://127.0.0.1" : normalized)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> {
                    codecs.defaultCodecs().maxInMemorySize(1024 * 1024);
                    codecs.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(MAPPER, MediaType.APPLICATION_JSON));
                    codecs.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(MAPPER, MediaType.APPLICATION_JSON));
                })
                .build();
        return new OllamaProviderClient(
                webClient,
                new OllamaClientSettings(normalized, "/api/chat", "/api/embed", apiKey),
                MAPPER);
    }
}
