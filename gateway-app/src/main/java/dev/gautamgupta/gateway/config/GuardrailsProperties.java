package dev.gautamgupta.gateway.config;

import dev.gautamgupta.gateway.guardrails.pii.GuardrailSettings;
import dev.gautamgupta.gateway.guardrails.pii.PiiMode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.guardrails")
public class GuardrailsProperties {

    private PiiMode mode = PiiMode.REDACT;
    private long maxRequestBytes = 1_048_576L;
    private Detectors detectors = new Detectors();
    private Map<String, Tenant> tenants = new LinkedHashMap<>();

    public PiiMode getMode() {
        return mode;
    }

    public void setMode(PiiMode mode) {
        this.mode = mode;
    }

    public long getMaxRequestBytes() {
        return maxRequestBytes;
    }

    public void setMaxRequestBytes(long maxRequestBytes) {
        this.maxRequestBytes = maxRequestBytes;
    }

    public Detectors getDetectors() {
        return detectors;
    }

    public void setDetectors(Detectors detectors) {
        this.detectors = detectors == null ? new Detectors() : detectors;
    }

    public Map<String, Tenant> getTenants() {
        return tenants;
    }

    public void setTenants(Map<String, Tenant> tenants) {
        this.tenants = tenants == null ? new LinkedHashMap<>() : tenants;
    }

    public GuardrailSettings toSettings() {
        Map<String, PiiMode> overrides = new LinkedHashMap<>();
        tenants.forEach((tenantId, tenant) -> {
            if (tenantId != null && !tenantId.isBlank() && tenant != null && tenant.getMode() != null) {
                overrides.put(tenantId, tenant.getMode());
            }
        });
        Detectors flags = detectors == null ? new Detectors() : detectors;
        return new GuardrailSettings(
                mode == null ? PiiMode.REDACT : mode,
                overrides,
                flags.isEmail(),
                flags.isPhone(),
                flags.isCreditCard(),
                flags.isIpv4(),
                flags.isIpv6(),
                flags.isIban(),
                flags.isPan(),
                flags.isAadhaar());
    }

    public static class Detectors {
        private boolean email = true;
        private boolean phone = true;
        private boolean creditCard = true;
        private boolean ipv4 = true;
        private boolean ipv6 = true;
        private boolean iban = true;
        private boolean pan = true;
        private boolean aadhaar = true;

        public boolean isEmail() {
            return email;
        }

        public void setEmail(boolean email) {
            this.email = email;
        }

        public boolean isPhone() {
            return phone;
        }

        public void setPhone(boolean phone) {
            this.phone = phone;
        }

        public boolean isCreditCard() {
            return creditCard;
        }

        public void setCreditCard(boolean creditCard) {
            this.creditCard = creditCard;
        }

        public boolean isIpv4() {
            return ipv4;
        }

        public void setIpv4(boolean ipv4) {
            this.ipv4 = ipv4;
        }

        public boolean isIpv6() {
            return ipv6;
        }

        public void setIpv6(boolean ipv6) {
            this.ipv6 = ipv6;
        }

        public boolean isIban() {
            return iban;
        }

        public void setIban(boolean iban) {
            this.iban = iban;
        }

        public boolean isPan() {
            return pan;
        }

        public void setPan(boolean pan) {
            this.pan = pan;
        }

        public boolean isAadhaar() {
            return aadhaar;
        }

        public void setAadhaar(boolean aadhaar) {
            this.aadhaar = aadhaar;
        }
    }

    public static class Tenant {
        private PiiMode mode;

        public PiiMode getMode() {
            return mode;
        }

        public void setMode(PiiMode mode) {
            this.mode = mode;
        }
    }
}
