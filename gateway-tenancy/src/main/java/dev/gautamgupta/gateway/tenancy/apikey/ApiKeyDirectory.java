package dev.gautamgupta.gateway.tenancy.apikey;

import reactor.core.publisher.Mono;

/** Lookup by key id. Empty when the id is not stored. */
public interface ApiKeyDirectory {

    Mono<ApiKeyRecord> find(String keyId);
}
