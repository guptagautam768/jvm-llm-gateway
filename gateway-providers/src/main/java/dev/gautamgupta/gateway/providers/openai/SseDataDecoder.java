package dev.gautamgupta.gateway.providers.openai;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;

/**
 * Turns a byte stream of Server-Sent Events into the {@code data:} payloads.
 * Events are separated by a blank line. A split UTF-8 character or a split line
 * is buffered until the rest arrives.
 */
public final class SseDataDecoder {

    private SseDataDecoder() {
    }

    public static Flux<String> decode(Flux<DataBuffer> buffers) {
        return Flux.defer(() -> {
            State state = new State();
            return buffers
                    .concatMap(buffer -> Flux.fromIterable(state.accept(buffer)))
                    .concatWith(Flux.defer(() -> Flux.fromIterable(state.finish())));
        });
    }

    private static final class State {
        private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        private ByteBuffer pendingBytes = ByteBuffer.allocate(0);
        private final StringBuilder lineBuffer = new StringBuilder();
        private final List<String> dataLines = new ArrayList<>();

        List<String> accept(DataBuffer buffer) {
            byte[] bytes = readBytes(buffer);
            return pushText(decodeBytes(bytes, false));
        }

        List<String> finish() {
            List<String> events = new ArrayList<>(pushText(decodeBytes(new byte[0], true)));
            if (!lineBuffer.isEmpty()) {
                String tail = lineBuffer.toString();
                lineBuffer.setLength(0);
                if (tail.endsWith("\r")) {
                    tail = tail.substring(0, tail.length() - 1);
                }
                onLine(tail, events);
            }
            flush(events);
            return events;
        }

        private List<String> pushText(String text) {
            lineBuffer.append(text);
            List<String> events = new ArrayList<>();
            while (true) {
                int newline = indexOf(lineBuffer, '\n');
                if (newline < 0) {
                    break;
                }
                String line = lineBuffer.substring(0, newline);
                lineBuffer.delete(0, newline + 1);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                onLine(line, events);
            }
            return events;
        }

        private void onLine(String line, List<String> events) {
            if (line.isEmpty()) {
                flush(events);
                return;
            }
            if (line.charAt(0) == ':') {
                return;
            }
            if (line.startsWith("data:")) {
                String value = line.substring("data:".length());
                if (!value.isEmpty() && value.charAt(0) == ' ') {
                    value = value.substring(1);
                }
                dataLines.add(value);
            }
        }

        private void flush(List<String> events) {
            if (dataLines.isEmpty()) {
                return;
            }
            events.add(String.join("\n", dataLines));
            dataLines.clear();
        }

        private String decodeBytes(byte[] incoming, boolean endOfInput) {
            ByteBuffer input = ByteBuffer.allocate(pendingBytes.remaining() + incoming.length);
            input.put(pendingBytes);
            input.put(incoming);
            input.flip();
            CharBuffer out = CharBuffer.allocate(Math.max(8, input.remaining() * 2));
            while (true) {
                CoderResult result = decoder.decode(input, out, endOfInput);
                if (result.isOverflow()) {
                    CharBuffer bigger = CharBuffer.allocate(out.capacity() * 2);
                    out.flip();
                    bigger.put(out);
                    out = bigger;
                    continue;
                }
                if (result.isError()) {
                    try {
                        result.throwException();
                    } catch (CharacterCodingException ex) {
                        throw new IllegalStateException("UTF-8 decode failed", ex);
                    }
                }
                break;
            }
            if (endOfInput) {
                CoderResult flushed = decoder.flush(out);
                if (flushed.isOverflow()) {
                    CharBuffer bigger = CharBuffer.allocate(out.capacity() * 2);
                    out.flip();
                    bigger.put(out);
                    out = bigger;
                    decoder.flush(out);
                }
            }
            pendingBytes = ByteBuffer.allocate(input.remaining());
            pendingBytes.put(input);
            pendingBytes.flip();
            out.flip();
            return out.toString();
        }

        private static byte[] readBytes(DataBuffer buffer) {
            try {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                return bytes;
            } finally {
                DataBufferUtils.release(buffer);
            }
        }

        private static int indexOf(StringBuilder builder, char target) {
            for (int i = 0; i < builder.length(); i++) {
                if (builder.charAt(i) == target) {
                    return i;
                }
            }
            return -1;
        }
    }
}
