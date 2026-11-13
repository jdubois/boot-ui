package io.github.jdubois.bootui.engine.exceptions;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shared normalization and exposure helpers for per-occurrence validation and binding diagnostics.
 */
public final class BindingDiagnostics {

    /** Maximum number of binding diagnostics retained per exception occurrence. */
    public static final int MAX_DIAGNOSTICS_PER_OCCURRENCE = 10;

    /** Maximum character length of a single diagnostic target, path, or reason string. */
    public static final int MAX_FIELD_LENGTH = 256;

    /** Placeholder used for unknown JSON properties when value exposure is not {@code FULL}. */
    public static final String UNRECOGNIZED_PROPERTY_PLACEHOLDER = "(unrecognized property)";

    private static final Pattern NUMERIC_OR_EMPTY_INDEX = Pattern.compile("\\[(?:\\d+|\\s*)\\]");

    private static final Pattern ANY_BRACKET_SEGMENT = Pattern.compile("\\[[^\\]\\r\\n]*\\]");

    private BindingDiagnostics() {}

    /**
     * Normalizes numeric array/list indices ({@code items[0]} or {@code items[]}) to {@code items[*]}.
     */
    public static String normalizeNumericIndices(String fieldPath) {
        if (fieldPath == null || fieldPath.isBlank()) {
            return "(unknown)";
        }
        return truncate(NUMERIC_OR_EMPTY_INDEX.matcher(fieldPath.trim()).replaceAll("[*]"));
    }

    /**
     * Applies the live value-exposure policy to a recorded diagnostic's {@code fieldPath}.
     *
     * <p>Under {@code MASKED} and {@code METADATA_ONLY} ({@code fullExposure == false}), unrecognized
     * JSON property names become {@code "(unrecognized property)"} and any bracketed map keys
     * ({@code attributes['secretKey']} or {@code attributes[secretKey]}) are redacted to
     * {@code attributes[*]}. Numeric list indices are always normalized to {@code [*]}.</p>
     */
    public static String exposeFieldPath(String fieldPath, boolean unrecognizedProperty, boolean fullExposure) {
        if (unrecognizedProperty && !fullExposure) {
            return UNRECOGNIZED_PROPERTY_PLACEHOLDER;
        }
        String normalized = normalizeNumericIndices(fieldPath);
        if (!fullExposure) {
            normalized = ANY_BRACKET_SEGMENT.matcher(normalized).replaceAll("[*]");
        }
        return truncate(normalized);
    }

    /**
     * Appends one Jackson reference-chain step to {@code path}, normalizing numeric indices to
     * {@code [*]} and wrapping map keys in {@code ['...']} so exposure filtering can redact them when
     * {@code bootui.expose-values} is not {@code FULL}.
     */
    public static void appendJsonPathSegment(StringBuilder path, int index, String propertyName, boolean mapReference) {
        if (index >= 0) {
            if (path.isEmpty()) {
                path.append("[*]");
            } else {
                path.append("[*]");
            }
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

    /** Whether a Jackson path reference's {@code from} object or class represents a {@link Map}. */
    public static boolean isMapReference(Object from) {
        if (from instanceof Map<?, ?>) {
            return true;
        }
        if (from instanceof Class<?> clazz) {
            return Map.class.isAssignableFrom(clazz);
        }
        return false;
    }

    /** Returns the simple class name of {@code type}, stripping synthetic/proxy suffixes. */
    public static String simpleTypeName(Class<?> type) {
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

    /** Returns a clean simple type or handler name, stripping package prefixes and proxy suffixes. */
    public static String simpleTypeName(String rawName) {
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

    public static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_FIELD_LENGTH ? value : value.substring(0, MAX_FIELD_LENGTH) + "…";
    }

    public static String truncateKey(Object key) {
        if (key == null) {
            return "*";
        }
        String text = String.valueOf(key).replace('\r', ' ').replace('\n', ' ').replace(']', '_');
        return text.length() <= 64 ? text : text.substring(0, 64) + "…";
    }
}
