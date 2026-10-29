package dev.gautamgupta.gateway.providers.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class SseDataDecoderTest {

    private final DefaultDataBufferFactory buffers = new DefaultDataBufferFactory();

    @Test
    void reassemblesEventsSplitAcrossChunks() {
        String payload = """
                data: {"id":"1","choices":[{"delta":{"content":"Hi"}}]}

                data: [DONE]

                """;
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        int split = payload.indexOf("choices");

        StepVerifier.create(SseDataDecoder.decode(Flux.just(
                buffers.wrap(Arrays.copyOfRange(bytes, 0, split)),
                buffers.wrap(Arrays.copyOfRange(bytes, split, bytes.length)))))
                .expectNext("{\"id\":\"1\",\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}")
                .expectNext("[DONE]")
                .verifyComplete();
    }

    @Test
    void decodesAUtf8CharacterSplitAcrossBuffers() {
        byte[] hello = "data: {\"t\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] accent = "é".getBytes(StandardCharsets.UTF_8);
        byte[] tail = "\"}\n\n".getBytes(StandardCharsets.UTF_8);
        assertThat(accent).hasSize(2);

        Flux<DataBuffer> chunks = Flux.just(
                buffers.wrap(concat(hello, Arrays.copyOf(accent, 1))),
                buffers.wrap(concat(Arrays.copyOfRange(accent, 1, 2), tail)));

        StepVerifier.create(SseDataDecoder.decode(chunks))
                .expectNext("{\"t\":\"é\"}")
                .verifyComplete();
    }

    @Test
    void acceptsCrLfSeparators() {
        String payload = "data: one\r\n\r\ndata: two\r\n\r\n";
        StepVerifier.create(SseDataDecoder.decode(Flux.just(buffers.wrap(payload.getBytes(StandardCharsets.UTF_8)))))
                .expectNext("one")
                .expectNext("two")
                .verifyComplete();
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] combined = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, combined, left.length, right.length);
        return combined;
    }
}
