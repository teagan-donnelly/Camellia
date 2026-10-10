package com.camellia.ingestion;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared Jackson 3 mapper plus null-safe tree helpers. */
public final class Json {

    public static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private Json() {}

    public static JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    /** Text of a node, or null when missing / JSON null / blank. */
    public static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String s = node.asString();
        return s == null || s.isBlank() ? null : s;
    }

    /** Follows a dotted path; numeric segments index arrays, e.g. "code.coding.0.display". */
    public static JsonNode at(JsonNode root, String dottedPath) {
        JsonNode cur = root;
        for (String seg : dottedPath.split("\\.")) {
            if (cur == null) return null;
            cur = seg.chars().allMatch(Character::isDigit) ? cur.path(Integer.parseInt(seg)) : cur.path(seg);
        }
        return cur;
    }

    public static String textAt(JsonNode root, String dottedPath) {
        return text(at(root, dottedPath));
    }

    public static Double number(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        return node.isNumber() ? node.asDouble() : parseDouble(node.asString());
    }

    static Double parseDouble(String s) {
        try {
            return s == null || s.isBlank() ? null : Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
