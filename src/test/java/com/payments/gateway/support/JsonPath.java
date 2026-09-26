package com.payments.gateway.support;

import java.util.List;
import java.util.Map;

/** Navigation over parsed JSON maps using dotted paths, e.g. {@code "latest_attempt.status"}. */
public final class JsonPath {

    private JsonPath() {
    }

    public static Object at(Map<String, Object> root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else if (current instanceof List<?> list && part.matches("\\d+")) {
                current = list.get(Integer.parseInt(part));
            } else {
                return null;
            }
        }
        return current;
    }

    public static String str(Map<String, Object> root, String path) {
        Object value = at(root, path);
        return value == null ? null : value.toString();
    }

    public static long num(Map<String, Object> root, String path) {
        Object value = at(root, path);
        if (!(value instanceof Number number)) {
            throw new AssertionError("Expected number at " + path + " but was " + value + " in " + root);
        }
        return number.longValue();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> list(Map<String, Object> root, String path) {
        return (List<Map<String, Object>>) at(root, path);
    }
}
