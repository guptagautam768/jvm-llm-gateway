package dev.gautamgupta.gateway.tenancy.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import dev.gautamgupta.gateway.tenancy.error.TenancyException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TenantAuthenticatorTest {

    private static final String SECRET = ApiKeyTokenTest.secret("acme");
    private static final String PRESENTED = "lgw_acmekey_" + SECRET;
    private static final TenantLimits LIMITS = new TenantLimits(10, 1, null, null, null, Duration.ofDays(30));

    @Test
    void acceptsAnActiveKey() {
        TenantAuthenticator authenticator = new TenantAuthenticator(keyId -> Mono.just(record(
                keyId, ApiKeyRecord.KeyStatus.ACTIVE, ApiKeyRecord.TenantStatus.ACTIVE)));

        StepVerifier.create(authenticator.authenticate(PRESENTED))
                .assertNext(record -> assertThat(record.tenantId()).isEqualTo("acme"))
                .verifyComplete();
    }

    @Test
    void unknownKeyAndWrongSecretAre401() {
        TenantAuthenticator unknown = new TenantAuthenticator(keyId -> Mono.empty());
        TenantAuthenticator wrong = new TenantAuthenticator(keyId -> Mono.just(record(
                keyId, ApiKeyRecord.KeyStatus.ACTIVE, ApiKeyRecord.TenantStatus.ACTIVE)));

        StepVerifier.create(unknown.authenticate(PRESENTED))
                .expectErrorSatisfies(error -> assertStatus(error, 401, "invalid_api_key"))
                .verify();
        StepVerifier.create(wrong.authenticate("lgw_acmekey_" + ApiKeyTokenTest.secret("nope")))
                .expectErrorSatisfies(error -> assertStatus(error, 401, "invalid_api_key"))
                .verify();
        StepVerifier.create(wrong.authenticate("not-a-key"))
                .expectErrorSatisfies(error -> assertStatus(error, 401, "invalid_api_key"))
                .verify();
    }

    @Test
    void revokedKeyIs401AndDisabledTenantIs403() {
        TenantAuthenticator revoked = new TenantAuthenticator(keyId -> Mono.just(record(
                keyId, ApiKeyRecord.KeyStatus.REVOKED, ApiKeyRecord.TenantStatus.ACTIVE)));
        TenantAuthenticator disabled = new TenantAuthenticator(keyId -> Mono.just(record(
                keyId, ApiKeyRecord.KeyStatus.ACTIVE, ApiKeyRecord.TenantStatus.DISABLED)));

        StepVerifier.create(revoked.authenticate(PRESENTED))
                .expectErrorSatisfies(error -> assertStatus(error, 401, "invalid_api_key"))
                .verify();
        StepVerifier.create(disabled.authenticate(PRESENTED))
                .expectErrorSatisfies(error -> assertStatus(error, 403, "tenant_disabled"))
                .verify();
    }

    @Test
    void directoryFailureIs503() {
        TenantAuthenticator authenticator = new TenantAuthenticator(keyId -> Mono.error(new IllegalStateException("db")));

        StepVerifier.create(authenticator.authenticate(PRESENTED))
                .expectErrorSatisfies(error -> assertStatus(error, 503, "dependency_unavailable"))
                .verify();
    }

    @Test
    void cacheCollapsesRepeatedLookupsAndCanBeDisabled() {
        AtomicInteger loads = new AtomicInteger();
        ApiKeyDirectory delegate = keyId -> {
            loads.incrementAndGet();
            return Mono.just(record(keyId, ApiKeyRecord.KeyStatus.ACTIVE, ApiKeyRecord.TenantStatus.ACTIVE));
        };
        CachingApiKeyDirectory cached = new CachingApiKeyDirectory(delegate, Duration.ofMinutes(1), 10);
        StepVerifier.create(cached.find("acmekey")).expectNextCount(1).verifyComplete();
        StepVerifier.create(cached.find("acmekey")).expectNextCount(1).verifyComplete();
        assertThat(loads).hasValue(1);

        AtomicInteger uncached = new AtomicInteger();
        CachingApiKeyDirectory bypass = new CachingApiKeyDirectory(keyId -> {
            uncached.incrementAndGet();
            return Mono.empty();
        }, Duration.ZERO, 10);
        StepVerifier.create(bypass.find("missing")).verifyComplete();
        StepVerifier.create(bypass.find("missing")).verifyComplete();
        assertThat(uncached).hasValue(2);
    }

    private static ApiKeyRecord record(String keyId, ApiKeyRecord.KeyStatus keyStatus, ApiKeyRecord.TenantStatus tenantStatus) {
        return new ApiKeyRecord(keyId, "acme", ApiKeyHasher.hash(SECRET), keyStatus, tenantStatus, LIMITS, true);
    }

    private static void assertStatus(Throwable error, int status, String code) {
        assertThat(error).isInstanceOf(TenancyException.class);
        TenancyException tenancy = (TenancyException) error;
        assertThat(tenancy.httpStatus()).isEqualTo(status);
        assertThat(tenancy.code()).isEqualTo(code);
    }
}
