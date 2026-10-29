package dev.gautamgupta.gateway.providers.anthropic;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
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
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

class AnthropicProviderClientTest {

    private static final WireMockServer WIRE_MOCK = start();
    private static final ObjectMapper MAPPER = GatewayJson.create();

    private AnthropicProviderClient client;

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
        client = client("sk-ant-test", "http://127.0.0.1:" + WIRE_MOCK.port(), Duration.ofSeconds(2));
    }

    @Test
    void translatesANonStreamingChatCompletion() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "msg_1",
                                  "type": "message",
                                  "role": "assistant",
                                  "model": "claude-haiku-4-5",
                                  "content": [{"type": "text", "text": "Hello from Claude"}],
                                  "stop_reason": "end_turn",
                                  "usage": {"input_tokens": 5, "output_tokens": 4}
                                }
                                """)));

        StepVerifier.create(client.complete(request(false)))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo("msg_1");
                    assertThat(response.object()).isEqualTo("chat.completion");
                    assertThat(response.choices().get(0).message().content().asText()).isEqualTo("Hello from Claude");
                    assertThat(response.choices().get(0).finishReason()).isEqualTo("stop");
                    assertThat(response.usage().totalTokens()).isEqualTo(9);
                })
                .verifyComplete();

        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withHeader(AnthropicProviderClient.API_KEY_HEADER, equalTo("sk-ant-test"))
                .withHeader(AnthropicProviderClient.VERSION_HEADER, equalTo("2023-06-01"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("claude-haiku-4-5")))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("16")))
                .withRequestBody(matchingJsonPath("$.system", equalTo("Be brief.")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("Hello"))));
        assertThat(WIRE_MOCK.getAllServeEvents().get(0).getRequest().getHeader("Authorization")).isNull();
    }

    @Test
    void translatesAStreamingChatCompletion() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("""
                                event: message_start
                                data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-4-5","content":[],"usage":{"input_tokens":2,"output_tokens":1}}}

                                event: content_block_delta
                                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}

                                event: message_delta
                                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}

                                event: message_stop
                                data: {"type":"message_stop"}

                                """)));

        StepVerifier.create(client.stream(request(true)))
                .assertNext(chunk -> assertThat(chunk.choices().get(0).delta().role()).isEqualTo("assistant"))
                .assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content().asText()).isEqualTo("Hello"))
                .assertNext(chunk -> {
                    assertThat(chunk.object()).isEqualTo("chat.completion.chunk");
                    assertThat(chunk.choices().get(0).finishReason()).isEqualTo("stop");
                    assertThat(chunk.usage().totalTokens()).isEqualTo(5);
                })
                .verifyComplete();

        WIRE_MOCK.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("Hello"))));
    }

    @Test
    void normalizesAnUpstreamError() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}
                                """)));

        StepVerifier.create(client.complete(request(false)))
                .expectErrorSatisfies(error -> {
                    ProviderException providerException = (ProviderException) error;
                    assertThat(providerException.status()).isEqualTo(401);
                    assertThat(providerException.responseBody()).contains("invalid x-api-key");
                    assertThat(providerException.responseBody()).contains("authentication_error");
                })
                .verify();
    }

    @Test
    void missingKeyDoesNotCallTheNetwork() {
        AnthropicProviderClient unconfigured = client("", "http://127.0.0.1:" + WIRE_MOCK.port(), Duration.ofSeconds(2));
        assertThatThrownBy(unconfigured::ensureConfigured)
                .isInstanceOf(ProviderException.class)
                .extracting(error -> ((ProviderException) error).kind())
                .isEqualTo(ProviderException.Kind.NOT_CONFIGURED);
        assertThat(unconfigured.id()).isEqualTo(ProviderIds.ANTHROPIC);
        assertThat(unconfigured.capabilities().chat()).isTrue();
        assertThat(unconfigured.capabilities().embeddings()).isFalse();
        assertThat(WIRE_MOCK.getAllServeEvents()).isEmpty();
    }

    private static ChatCompletionRequest request(boolean stream) {
        return new ChatCompletionRequest(
                "claude-haiku-4-5",
                List.of(
                        new ChatCompletionRequest.ChatMessage("system", MAPPER.getNodeFactory().textNode("Be brief."), null, null, null),
                        new ChatCompletionRequest.ChatMessage("user", MAPPER.getNodeFactory().textNode("Hello"), null, null, null)),
                0.2,
                null,
                null,
                stream,
                stream ? new ChatCompletionRequest.StreamOptions(true) : null,
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

    private static AnthropicProviderClient client(String apiKey, String baseUrl, Duration responseTimeout) {
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
        return new AnthropicProviderClient(
                webClient,
                new AnthropicClientSettings(apiKey, "/v1/messages", "2023-06-01", 4096),
                MAPPER);
    }
}
