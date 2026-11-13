package io.github.jdubois.bootui.engine.exceptions;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shared normalization and exposure helpers for per-occurrence validation and binding diagnostics.
 */
final class BindingDiagnostics {

    static final int MAX_DIAGNOSTICS_PER_OCCURRENCE = 10;

    static final int MAX_FIELD_LENGTH = 256;

    static final String UNRECOGNIZED_PROPERTY_PLACEHOLDER = "(unrecognized property)";

    private static final Pattern NUMERIC_OR_EMPTY_INDEX = Pattern.compile("\\[(?:\\d+|\\s*)\\]");

    private static final Pattern ANY_BRACKET_SEGMENT = Pattern.compile("\\[[^\\]]*(?:\\]|$)", Pattern.DOTALL);

    private BindingDiagnostics() {}

    static String normalizeNumericIndices(String fieldPath) {
        if (fieldPath == null || fieldPath.isBlank()) {
            return "(unknown)";
        }
        return NUMERIC_OR_EMPTY_INDEX.matcher(fieldPath.trim()).replaceAll("[*]");
    }

    static String exposeFieldPath(String fieldPath, boolean unrecognizedProperty, boolean fullExposure) {
        if (unrecognizedProperty && !fullExposure) {
            return UNRECOGNIZED_PROPERTY_PLACEHOLDER;
        }
        String normalized = normalizeNumericIndices(fieldPath);
        if (!fullExposure) {
            normalized = ANY_BRACKET_SEGMENT.matcher(normalized).replaceAll("[*]");
        }
        return truncate(normalized);
    }

    static void appendJsonPathSegment(StringBuilder path, int index, String propertyName, boolean mapReference) {
        if (index >= 0) {
            path.append("[*]");
            return;
        }
        if (propertyName == null || propertyName.isEmpty()) {
            return;
        }
        if (mapReference) {
            path.append("['").append(truncateKey(propertyName)).append("']");
            return;
        }
        if (!path.isEmpty()) {
            path.append('.');
        }
        path.append(propertyName);
    }

    static boolean isMapReference(Object from) {
        if (from instanceof Map<?, ?>) {
            return true;
        }
        if (from instanceof Class<?> clazz) {
            return Map.class.isAssignableFrom(clazz);
        }
        return false;
    }

    static String simpleTypeName(Class<?> type) {
        if (type == null) {
            return null;
        }
        if (type.isPrimitive()) {
            return switch (type.getName()) {
                case "int" -> "Integer";
                case "long" -> "Long";
                case "double" -> "Double";
                case "boolean" -> "Boolean";
                case "float" -> "Float";
                case "short" -> "Short";
                case "byte" -> "Byte";
                case "char" -> "Character";
                default -> type.getSimpleName();
            };
        }
        if (type.isArray()) {
            return simpleTypeName(type.getComponentType()) + "[]";
        }
        String simple = type.getSimpleName();
        return simpleTypeName(simple.isEmpty() ? type.getName() : simple);
    }

    static String simpleTypeName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String name = rawName.trim();
        int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < name.length()) {
            name = name.substring(lastDot + 1);
        }
        for (String marker : List.of("$$", "_Subclass", "_ClientProxy", "_HibernateProxy")) {
            int idx = name.indexOf(marker);
            if (idx > 0) {
                name = name.substring(0, idx);
            }
        }
        int dollar = name.lastIndexOf('$');
        if (dollar >= 0 && dollar + 1 < name.length() && !Character.isDigit(name.charAt(dollar + 1))) {
            name = name.substring(dollar + 1);
        }
        return truncate(name);
    }

    static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_FIELD_LENGTH ? value : value.substring(0, MAX_FIELD_LENGTH) + "…";
    }

    static String truncateKey(Object key) {
        if (key == null) {
            return "*";
        }
        String text = String.valueOf(key).replace('\r', ' ').replace('\n', ' ').replace(']', '_');
        return text.length() <= 64 ? text : text.substring(0, 64) + "…";
    }
}
