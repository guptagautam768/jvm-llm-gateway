package dev.gautamgupta.gateway.guardrails.pii.checksum;

/** Luhn check used by the credit-card detector (ADR-0009). */
public final class Luhn {

    private Luhn() {
    }

    public static boolean valid(String digits) {
        if (digits == null || digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            int n = c - '0';
            if (doubleIt) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
