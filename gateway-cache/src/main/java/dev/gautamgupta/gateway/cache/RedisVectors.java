package dev.gautamgupta.gateway.cache;

import io.lettuce.core.api.reactive.RedisReactiveCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.NestedMultiOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import java.nio.charset.StandardCharsets;
import reactor.core.publisher.Mono;

/** RediSearch commands. Spring's string Redis template cannot carry the vector blob. */
final class RedisVectors {

    private final RedisReactiveCommands<byte[], byte[]> commands;

    RedisVectors(RedisReactiveCommands<byte[], byte[]> commands) {
        this.commands = commands;
    }

    Mono<Void> createIndex(int dimensions) {
        return call(
                        "FT.CREATE",
                        bytes(RedisResponseCache.INDEX),
                        bytes("ON"),
                        bytes("HASH"),
                        bytes("PREFIX"),
                        1L,
                        bytes(ExactCacheKey.SEMANTIC_PREFIX),
                        bytes("SCHEMA"),
                        bytes("tenant"),
                        bytes("TAG"),
                        bytes("CASESENSITIVE"),
                        bytes("model"),
                        bytes("TAG"),
                        bytes("CASESENSITIVE"),
                        bytes("context"),
                        bytes("TAG"),
                        bytes("CASESENSITIVE"),
                        bytes("payload"),
                        bytes("TEXT"),
                        bytes("NOINDEX"),
                        bytes("embedding"),
                        bytes("VECTOR"),
                        bytes("HNSW"),
                        6L,
                        bytes("TYPE"),
                        bytes("FLOAT32"),
                        bytes("DIM"),
                        (long) dimensions,
                        bytes("DISTANCE_METRIC"),
                        bytes("COSINE"))
                .then();
    }

    Mono<SearchReply.Result> nearest(String tenant, String model, String context, float[] vector) {
        String query = "(@tenant:{" + TagValue.escape(tenant)
                + "} @model:{" + TagValue.escape(model)
                + "} @context:{" + TagValue.escape(context)
                + "})=>[KNN 1 @embedding $vec AS dist]";
        return call(
                        "FT.SEARCH",
                        bytes(RedisResponseCache.INDEX),
                        bytes(query),
                        bytes("PARAMS"),
                        2L,
                        bytes("vec"),
                        VectorBytes.toBytes(vector),
                        bytes("DIALECT"),
                        2L,
                        bytes("LIMIT"),
                        0L,
                        1L,
                        bytes("RETURN"),
                        2L,
                        bytes("payload"),
                        bytes("dist"))
                .flatMap(reply -> {
                    SearchReply.Result result = SearchReply.parse(reply);
                    if (result == null) {
                        return Mono.empty();
                    }
                    return Mono.just(result);
                });
    }

    private Mono<Object> call(String command, Object... args) {
        CommandArgs<byte[], byte[]> commandArgs = new CommandArgs<>(ByteArrayCodec.INSTANCE);
        for (Object arg : args) {
            if (arg instanceof byte[] blob) {
                commandArgs.add(blob);
            } else if (arg instanceof Long number) {
                commandArgs.add(number.longValue());
            } else {
                throw new IllegalArgumentException("unsupported redis argument");
            }
        }
        ProtocolKeyword keyword = keyword(command);
        return commands.dispatch(keyword, new NestedMultiOutput<>(ByteArrayCodec.INSTANCE), commandArgs)
                .next()
                .map(value -> (Object) value);
    }

    private static ProtocolKeyword keyword(String command) {
        byte[] bytes = command.getBytes(StandardCharsets.US_ASCII);
        return new ProtocolKeyword() {
            @Override
            public byte[] getBytes() {
                return bytes;
            }
        };
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
