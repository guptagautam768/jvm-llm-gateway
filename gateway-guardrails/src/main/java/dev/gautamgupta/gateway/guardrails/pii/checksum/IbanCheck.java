package dev.gautamgupta.gateway.guardrails.pii.checksum;

/** ISO 13616 mod-97 check. Letters are expanded to A=10 … Z=35. */
public final class IbanCheck {

    private IbanCheck() {
    }

    public static boolean valid(String compact) {
        if (compact == null) {
            return false;
        }
        int length = compact.length();
        if (length < 15 || length > 34) {
            return false;
        }
        String moved = compact.substring(4) + compact.substring(0, 4);
        int mod = 0;
        for (int i = 0; i < moved.length(); i++) {
            char c = moved.charAt(i);
            int value;
            if (c >= '0' && c <= '9') {
                value = c - '0';
                mod = (mod * 10 + value) % 97;
            } else if (c >= 'A' && c <= 'Z') {
                value = c - 'A' + 10;
                mod = (mod * 10 + (value / 10)) % 97;
                mod = (mod * 10 + (value % 10)) % 97;
            } else {
                return false;
            }
        }
        return mod == 1;
    }
}
