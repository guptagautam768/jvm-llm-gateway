package dev.gautamgupta.gateway.providers.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.api.json.GatewayJson;
import dev.gautamgupta.gateway.core.provider.ProviderException;
import dev.gautamgupta.gateway.core.provider.ProviderIds;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

class OpenAiProviderClientTest {

    private static final WireMockServer WIRE_MOCK = start();
    private static final ObjectMapper MAPPER = GatewayJson.create();

    private OpenAiProviderClient client;

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
        client = client("sk-test", "http://127.0.0.1:" + WIRE_MOCK.port(), Duration.ofSeconds(2));
    }

    @Test
    void forwardsANonStreamingChatCompletion() {
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

        StepVerifier.create(client.complete(request(false)))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo("chatcmpl-test");
                    assertThat(response.object()).isEqualTo("chat.completion");
                    assertThat(response.choices()).hasSize(1);
                    assertThat(response.choices().get(0).message().content().asText()).isEqualTo("Hello from upstream");
                    assertThat(response.usage().totalTokens()).isEqualTo(12);
                })
                .verifyComplete();

        var events = WIRE_MOCK.getAllServeEvents();
        assertThat(events).hasSize(1);
        String body = events.get(0).getRequest().getBodyAsString();
        assertThat(events.get(0).getRequest().getHeader("Authorization")).isEqualTo("Bearer sk-test");
        assertThat(body).contains("\"model\":\"gpt-4o-mini\"");
        assertThat(body).contains("\"content\":\"Hello\"");
        assertThat(body).contains("\"temperature\":0.2");
        assertThat(body).contains("\"max_tokens\":16");
        assertThat(body).doesNotContain("\"stream\"");
    }

    @Test
    void forwardsAStreamingChatCompletion() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("""
                                data: {"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":"assistant","content":"He"}}]}

                                data: {"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":"llo"},"finish_reason":"stop"}]}

                                data: [DONE]

                                """)));

        StepVerifier.create(client.stream(request(true)))
                .assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content().asText()).isEqualTo("He"))
                .assertNext(chunk -> {
                    assertThat(chunk.choices().get(0).delta().content().asText()).isEqualTo("llo");
                    assertThat(chunk.choices().get(0).finishReason()).isEqualTo("stop");
                })
                .verifyComplete();

        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer sk-test"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.stream", equalTo("true"))));
    }

    @Test
    void listsModelsWithTheSameKey() {
        WIRE_MOCK.stubFor(get(urlEqualTo("/v1/models"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"object":"list","data":[{"id":"gpt-4o-mini","object":"model","created":1,"owned_by":"openai"}]}
                                """)));

        StepVerifier.create(client.listModels())
                .assertNext(list -> {
                    assertThat(list.object()).isEqualTo("list");
                    assertThat(list.data()).singleElement().extracting("id").isEqualTo("gpt-4o-mini");
                })
                .verifyComplete();

        WIRE_MOCK.verify(getRequestedFor(urlEqualTo("/v1/models"))
                .withHeader("Authorization", equalTo("Bearer sk-test")));
    }

    @Test
    void passesUpstreamErrorsThrough() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Retry-After", "2")
                        .withBody("""
                                {"error":{"message":"Incorrect API key","type":"invalid_request_error","code":"invalid_api_key"}}
                                """)));

        StepVerifier.create(client.complete(request(false)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ProviderException.class);
                    ProviderException providerException = (ProviderException) error;
                    assertThat(providerException.kind()).isEqualTo(ProviderException.Kind.UPSTREAM);
                    assertThat(providerException.status()).isEqualTo(401);
                    assertThat(providerException.responseBody()).contains("invalid_api_key");
                    assertThat(providerException.retryAfter()).isEqualTo("2");
                })
                .verify();
    }

    @Test
    void missingKeyDoesNotCallTheNetwork() {
        OpenAiProviderClient unconfigured = client("  ", "http://127.0.0.1:" + WIRE_MOCK.port(), Duration.ofSeconds(2));
        assertThatThrownBy(unconfigured::ensureConfigured)
                .isInstanceOf(ProviderException.class)
                .extracting("kind")
                .isEqualTo(ProviderException.Kind.NOT_CONFIGURED);
        assertThat(WIRE_MOCK.getAllServeEvents()).isEmpty();
        assertThat(unconfigured.id()).isEqualTo(ProviderIds.OPENAI);
        assertThat(unconfigured.capabilities().chat()).isTrue();
        assertThat(unconfigured.capabilities().embeddings()).isFalse();
    }

    @Test
    void unreachableProviderIsAGatewayError() {
        OpenAiProviderClient offline = client("sk-test", "http://127.0.0.1:1", Duration.ofMillis(300));
        StepVerifier.create(offline.complete(request(false)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ProviderException.class);
                    assertThat(((ProviderException) error).kind())
                            .isIn(ProviderException.Kind.UNAVAILABLE, ProviderException.Kind.TIMEOUT);
                })
                .verify(Duration.ofSeconds(5));
    }

    private static ChatCompletionRequest request(boolean stream) {
        return new ChatCompletionRequest(
                "gpt-4o-mini",
                List.of(new ChatCompletionRequest.ChatMessage(
                        "user",
                        MAPPER.getNodeFactory().textNode("Hello"),
                        null,
                        null,
                        null)),
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

    private static OpenAiProviderClient client(String apiKey, String baseUrl, Duration responseTimeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 300)
                .responseTimeout(responseTimeout);
        WebClient webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> {
                    codecs.defaultCodecs().maxInMemorySize(2 * 1024 * 1024);
                    codecs.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(MAPPER, MediaType.APPLICATION_JSON));
                    codecs.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(MAPPER, MediaType.APPLICATION_JSON));
                })
                .build();
        return new OpenAiProviderClient(
                webClient,
                new OpenAiClientSettings(apiKey, "/v1/chat/completions", "/v1/models"),
                MAPPER);
    }
}
