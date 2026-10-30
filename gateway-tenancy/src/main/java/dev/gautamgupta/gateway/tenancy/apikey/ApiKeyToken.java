package dev.gautamgupta.gateway.tenancy.apikey;

import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code lgw_<keyId>_<secret>}. {@code keyId} is alphanumeric so the secret may contain
 * {@code _} (base64url). The secret decodes to at least 32 bytes (ADR-0007).
 */
public record ApiKeyToken(String keyId, String secret) {

    private static final Pattern TOKEN = Pattern.compile("^lgw_([A-Za-z0-9]{1,64})_([A-Za-z0-9_-]{43,})$");

    public static ApiKeyToken parse(String presented) {
        if (presented == null) {
            throw new IllegalArgumentException("missing api key");
        }
        String trimmed = presented.trim();
        Matcher matcher = TOKEN.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("malformed api key");
        }
        String secret = matcher.group(2);
        byte[] decoded = decode(secret);
        if (decoded.length < 32) {
            throw new IllegalArgumentException("api key secret is shorter than 32 bytes");
        }
        return new ApiKeyToken(matcher.group(1), secret);
    }

    public String asPresented() {
        return "lgw_" + keyId + "_" + secret;
    }

    private static byte[] decode(String secret) {
        int pad = (4 - secret.length() % 4) % 4;
        String padded = secret + "=".repeat(pad);
        try {
            return Base64.getUrlDecoder().decode(padded);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("api key secret is not base64url", ex);
        }
    }
}
