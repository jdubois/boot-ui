package io.github.jdubois.bootui.engine.telemetry;

/**
 * Shared hard bounds and truncation policy for captured telemetry attribute values.
 *
 * <p>Both the in-process {@link BootUiSpanExporter} (engine) and the adapter-side OTLP/HTTP decoder
 * coerce attribute strings to the same maximum length, so the clamp lives here to keep the two
 * capture paths byte-identical and prevent drift.</p>
 */
public final class TelemetryLimits {

    /** Absolute ceiling on a single attribute string value, independent of the configured limit. */
    public static final int HARD_MAX_ATTRIBUTE_VALUE_CHARS = 64 * 1024;

    private TelemetryLimits() {}

    /**
     * Truncate an attribute value to {@code max(64, min(HARD_MAX, maxAttributeValueBytes))} characters,
     * appending a marker that records how many characters were dropped.
     *
     * @param value the raw attribute string (may be {@code null})
     * @param maxAttributeValueBytes the configured per-value character budget
     * @return the original value when short enough, otherwise the truncated value with a marker
     */
    public static String truncate(String value, int maxAttributeValueBytes) {
        if (value == null) {
            return null;
        }
        int max = Math.max(64, Math.min(HARD_MAX_ATTRIBUTE_VALUE_CHARS, maxAttributeValueBytes));
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…[truncated " + (value.length() - max) + " chars]";
    }
}
