package dev.gautamgupta.gateway.tenancy.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class ApiKeyTokenTest {

    @Test
    void parsesKeyIdAndBase64UrlSecret() {
        String secret = secret("payload");
        ApiKeyToken token = ApiKeyToken.parse("lgw_acmekey_" + secret);

        assertThat(token.keyId()).isEqualTo("acmekey");
        assertThat(token.secret()).isEqualTo(secret);
        assertThat(token.asPresented()).isEqualTo("lgw_acmekey_" + secret);
    }

    @Test
    void secretMayContainUnderscores() {
        byte[] raw = new byte[32];
        raw[0] = (byte) 0xff;
        raw[1] = 0x00;
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        assertThat(secret).contains("_");

        ApiKeyToken token = ApiKeyToken.parse("lgw_key1_" + secret);
        assertThat(token.secret()).isEqualTo(secret);
    }

    @Test
    void rejectsMalformedTokens() {
        assertThatThrownBy(() -> ApiKeyToken.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiKeyToken.parse("sk-live")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiKeyToken.parse("lgw_bad+id_" + "a".repeat(43)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiKeyToken.parse("lgw_key_short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiKeyToken.parse("lgw_key_" + "*".repeat(43)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static String secret(String label) {
        byte[] raw = new byte[32];
        byte[] labelBytes = label.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(labelBytes, 0, raw, 0, Math.min(labelBytes.length, raw.length));
        for (int i = labelBytes.length; i < raw.length; i++) {
            raw[i] = (byte) (i + 1);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
