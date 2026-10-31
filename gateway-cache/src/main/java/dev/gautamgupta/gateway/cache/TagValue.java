package dev.gautamgupta.gateway.cache;

/** Escapes a RediSearch TAG value. The stored field stays raw; only the query is escaped. */
public final class TagValue {

    private static final String SPECIAL = ",.<>{}[]\"':;!@#$%^&*()-+=~|\\ ";

    private TagValue() {
    }

    public static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (SPECIAL.indexOf(c) >= 0) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}
