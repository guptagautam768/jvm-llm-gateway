package dev.gautamgupta.gateway.tenancy.apikey;

import dev.gautamgupta.gateway.tenancy.error.TenancyException;
import reactor.core.publisher.Mono;

/** Maps a presented gateway key to an active tenant, or a stable auth error. */
public final class TenantAuthenticator {

    private final ApiKeyDirectory directory;

    public TenantAuthenticator(ApiKeyDirectory directory) {
        this.directory = directory;
    }

    public Mono<ApiKeyRecord> authenticate(String presented) {
        final ApiKeyToken token;
        try {
            token = ApiKeyToken.parse(presented);
        } catch (IllegalArgumentException ex) {
            return Mono.error(TenancyException.invalidApiKey());
        }
        return directory.find(token.keyId())
                .onErrorMap(ex -> ex instanceof TenancyException tenancy
                        ? tenancy
                        : TenancyException.dependency(ex))
                .defaultIfEmpty(ApiKeyRecord.unknown(token.keyId()))
                .flatMap(record -> decide(record, token.secret()));
    }

    private static Mono<ApiKeyRecord> decide(ApiKeyRecord record, String secret) {
        byte[] expected = record.secretHash() == null ? ApiKeyHasher.UNKNOWN_HASH : record.secretHash();
        boolean hashOk = ApiKeyHasher.matches(expected, secret);
        if (!hashOk || !record.known() || record.keyStatus() != ApiKeyRecord.KeyStatus.ACTIVE) {
            return Mono.error(TenancyException.invalidApiKey());
        }
        if (record.tenantStatus() != ApiKeyRecord.TenantStatus.ACTIVE) {
            return Mono.error(TenancyException.tenantDisabled());
        }
        if (record.limits() == null) {
            return Mono.error(TenancyException.dependency(new IllegalStateException("tenant has no limits")));
        }
        return Mono.just(record);
    }
}
