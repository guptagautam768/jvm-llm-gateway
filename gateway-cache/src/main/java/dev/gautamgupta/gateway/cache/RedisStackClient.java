package dev.gautamgupta.gateway.cache;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import java.time.Duration;

/**
 * Byte-safe connection to the shared Redis Stack instance. Rate limits keep using
 * Spring's string connection. Cache vectors need a byte codec, so this is a second
 * connection to the same server, not a second server.
 */
public final class RedisStackClient implements AutoCloseable {

    private final RedisClient client;
    private final StatefulRedisConnection<byte[], byte[]> connection;

    private RedisStackClient(RedisClient client, StatefulRedisConnection<byte[], byte[]> connection) {
        this.client = client;
        this.connection = connection;
    }

    public static RedisStackClient connect(String host, int port, String username, String password, int database) {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(host == null || host.isBlank() ? "localhost" : host)
                .withPort(port)
                .withDatabase(database)
                .withTimeout(Duration.ofSeconds(5));
        if (password != null && !password.isBlank()) {
            if (username != null && !username.isBlank()) {
                builder.withAuthentication(username, password.toCharArray());
            } else {
                builder.withPassword(password.toCharArray());
            }
        }
        RedisClient client = RedisClient.create(builder.build());
        StatefulRedisConnection<byte[], byte[]> connection = client.connect(ByteArrayCodec.INSTANCE);
        return new RedisStackClient(client, connection);
    }

    RedisReactiveCommands<byte[], byte[]> commands() {
        return connection.reactive();
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
}
