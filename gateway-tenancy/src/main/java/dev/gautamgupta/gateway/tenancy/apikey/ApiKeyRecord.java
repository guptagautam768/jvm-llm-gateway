package dev.gautamgupta.gateway.tenancy.apikey;

public record ApiKeyRecord(
        String keyId,
        String tenantId,
        byte[] secretHash,
        KeyStatus keyStatus,
        TenantStatus tenantStatus,
        TenantLimits limits,
        boolean known
) {

    public enum KeyStatus {
        ACTIVE,
        REVOKED
    }

    public enum TenantStatus {
        ACTIVE,
        DISABLED
    }

    public static ApiKeyRecord unknown(String keyId) {
        return new ApiKeyRecord(
                keyId,
                "",
                ApiKeyHasher.UNKNOWN_HASH,
                KeyStatus.REVOKED,
                TenantStatus.DISABLED,
                null,
                false);
    }
}
