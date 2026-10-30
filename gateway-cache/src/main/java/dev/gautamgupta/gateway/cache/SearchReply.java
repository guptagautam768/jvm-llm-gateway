package dev.gautamgupta.gateway.cache;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses an {@code FT.SEARCH} reply into the payload and cosine distance. */
final class SearchReply {

    private SearchReply() {
    }

    record Result(byte[] key, byte[] payload, double distance) {
    }

    static Result parse(Object reply) {
        if (!(reply instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        if (list.get(0) instanceof Number || isNumericText(list.get(0))) {
            return parseResp2(list);
        }
        return parseMap(list);
    }

    /** Redis Stack 7.4 returns a DIALECT 2 map: total_results plus results[].extra_attributes. */
    private static Result parseMap(List<?> list) {
        Map<String, Object> top = asMap(list);
        if (asLong(top.get("total_results")) <= 0) {
            return null;
        }
        if (!(top.get("results") instanceof List<?> rows) || rows.isEmpty() || !(rows.get(0) instanceof List<?> row)) {
            return null;
        }
        Map<String, Object> document = asMap(row);
        if (!(document.get("extra_attributes") instanceof List<?> extra)) {
            return null;
        }
        return fromFields(asBytes(document.get("id")), asMap(extra));
    }

    private static Result parseResp2(List<?> list) {
        if (list.size() < 3 || asLong(list.get(0)) <= 0 || !(list.get(2) instanceof List<?> columns)) {
            return null;
        }
        return fromFields(asBytes(list.get(1)), asMap(columns));
    }

    private static Result fromFields(byte[] key, Map<String, Object> fields) {
        if (key == null) {
            return null;
        }
        byte[] payload = asBytes(fields.get("payload"));
        Double distance = asDouble(fields.get("dist"));
        if (distance == null) {
            distance = asDouble(fields.get("score"));
        }
        if (distance == null) {
            distance = asDouble(fields.get("__embedding_score"));
        }
        if (payload == null || distance == null) {
            return null;
        }
        return new Result(key, payload, distance);
    }

    private static Map<String, Object> asMap(List<?> list) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < list.size(); i += 2) {
            String key = asString(list.get(i));
            if (key != null) {
                map.put(key, list.get(i + 1));
            }
        }
        return map;
    }

    private static boolean isNumericText(Object value) {
        String text = asString(value);
        if (text == null) {
            return false;
        }
        try {
            Long.parseLong(text.trim());
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = asString(value);
        if (text == null) {
            return 0L;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = asString(value);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String asString(Object value) {
        byte[] bytes = asBytes(value);
        if (bytes == null) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] asBytes(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        if (value instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        if (value instanceof ByteBuffer buffer) {
            ByteBuffer copy = buffer.asReadOnlyBuffer();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            return bytes;
        }
        return null;
    }
}
