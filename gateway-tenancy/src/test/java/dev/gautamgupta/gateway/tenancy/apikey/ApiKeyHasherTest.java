package dev.gautamgupta.gateway.tenancy.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiKeyHasherTest {

    @Test
    void matchesOnlyTheSameSecret() {
        String secret = ApiKeyTokenTest.secret("same");
        byte[] hash = ApiKeyHasher.hash(secret);

        assertThat(hash).hasSize(32);
        assertThat(ApiKeyHasher.matches(hash, secret)).isTrue();
        assertThat(ApiKeyHasher.matches(hash, ApiKeyTokenTest.secret("other"))).isFalse();
        assertThat(ApiKeyHasher.matches(null, secret)).isFalse();
        assertThat(ApiKeyHasher.matches(new byte[] {1, 2, 3}, secret)).isFalse();
    }
}
