package dev.gautamgupta.gateway.guardrails.pii.checksum;

/**
 * Verhoeff check used for Aadhaar. UIDAI appends this digit.
 * Tables are the standard Verhoeff multiplication and permutation.
 */
public final class Verhoeff {

    private static final int[][] D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 2, 3, 4, 0, 6, 7, 8, 9, 5},
            {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7},
            {4, 0, 1, 2, 3, 9, 5, 6, 7, 8},
            {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2},
            {7, 6, 5, 9, 8, 2, 1, 0, 4, 3},
            {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}
    };

    private static final int[][] P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 5, 7, 6, 2, 8, 3, 0, 9, 4},
            {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7},
            {9, 4, 5, 3, 1, 2, 6, 8, 7, 0},
            {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5},
            {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}
    };

    private static final int[] INV = {0, 4, 3, 2, 1, 5, 6, 7, 8, 9};

    private Verhoeff() {
    }

    public static boolean valid(String digits) {
        if (digits == null || digits.isEmpty()) {
            return false;
        }
        int checksum = 0;
        int len = digits.length();
        for (int i = 0; i < len; i++) {
            char c = digits.charAt(len - 1 - i);
            if (c < '0' || c > '9') {
                return false;
            }
            checksum = D[checksum][P[i % 8][c - '0']];
        }
        return checksum == 0;
    }

    public static char checkDigit(String payload) {
        int checksum = 0;
        int len = payload.length();
        for (int i = 0; i < len; i++) {
            int digit = payload.charAt(len - 1 - i) - '0';
            checksum = D[checksum][P[(i + 1) % 8][digit]];
        }
        return (char) ('0' + INV[checksum]);
    }
}
