package dev.gautamgupta.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

class ChatTenancyWebFilterTest {

    @Test
    void bearerWinsAndApiKeyIsTheFallback() {
        assertThat(ChatTenancyWebFilter.credential(MockServerHttpRequest.post("/v1/chat/completions")
                .header("Authorization", "Bearer lgw_acme_secret")
                .header("api-key", "other")
                .build())).isEqualTo("lgw_acme_secret");

        assertThat(ChatTenancyWebFilter.credential(MockServerHttpRequest.post("/v1/chat/completions")
                .header("api-key", " lgw_acme_secret ")
                .build())).isEqualTo("lgw_acme_secret");

        assertThat(ChatTenancyWebFilter.credential(MockServerHttpRequest.post("/v1/chat/completions")
                .header("Authorization", "Basic abc")
                .header("api-key", "lgw_acme_secret")
                .build())).isNull();

        assertThat(ChatTenancyWebFilter.credential(MockServerHttpRequest.post("/v1/chat/completions").build())).isNull();
    }
}
