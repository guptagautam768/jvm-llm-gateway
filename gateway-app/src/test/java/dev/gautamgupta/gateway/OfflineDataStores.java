package dev.gautamgupta.gateway;

/**
 * Weeks 1 and 2 tests boot without Postgres or Redis. Spring Boot 3.5 refuses to
 * start when the R2DBC starter is present and no URL is set, so those auto-configurations
 * stay off unless a test opts in (see {@code TenancyLedgerIT}).
 */
final class OfflineDataStores {

    static final String EXCLUDE = "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.data.r2dbc.R2dbcRepositoriesAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration";

    static final String CACHE_OFF = "gateway.cache.enabled=false";

    private OfflineDataStores() {
    }
}
