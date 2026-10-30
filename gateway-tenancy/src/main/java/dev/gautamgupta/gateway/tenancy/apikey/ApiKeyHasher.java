package dev.gautamgupta.gateway.tenancy.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 of the key secret. Keys are high-entropy random values, so a fast hash
 * is the right comparison (ADR-0007). {@link MessageDigest#isEqual} is constant-time
 * for the fixed 32-byte digest.
 */
public final class ApiKeyHasher {

    /** Compared when the key id is unknown so the hash still runs. */
    public static final byte[] UNKNOWN_HASH = hash("gateway-unknown-key");

    private ApiKeyHasher() {
    }

    public static byte[] hash(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    public static boolean matches(byte[] expectedHash, String secret) {
        byte[] actual = hash(secret);
        byte[] expected = expectedHash == null || expectedHash.length != actual.length ? UNKNOWN_HASH : expectedHash;
        return MessageDigest.isEqual(expected, actual) && expectedHash != null && expectedHash.length == actual.length;
    }
}
