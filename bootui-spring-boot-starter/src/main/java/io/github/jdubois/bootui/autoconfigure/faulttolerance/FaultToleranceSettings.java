package io.github.jdubois.bootui.autoconfigure.faulttolerance;

import io.github.jdubois.bootui.core.dto.FaultTolerancePolicySettingDto;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Small builder for the ordered {@code settings} list on a policy, plus the shared rendering rules for
 * durations, percentages and counts.
 *
 * <p>It imports no fault tolerance library type, so it is safe to load from an always-loaded class. Provenance
 * is derived by comparing the effective value with the library's documented default: equal means
 * {@code DEFAULT}, different means {@code CONFIGURED}. A value BootUI cannot compare is reported as
 * {@code UNKNOWN} rather than guessed.</p>
 */
final class FaultToleranceSettings {

    private final List<FaultTolerancePolicySettingDto> settings = new ArrayList<>();

    /** Adds a setting whose provenance is derived by comparing {@code value} with {@code defaultValue}. */
    FaultToleranceSettings add(String name, Object value, Object defaultValue) {
        return addRendered(name, render(value), provenance(value, defaultValue));
    }

    /**
     * Adds a percentage setting, keeping the unit visible while still deriving provenance from the raw
     * numeric comparison.
     */
    FaultToleranceSettings addPercent(String name, float value, float defaultValue) {
        return addRendered(name, percent(value), provenance(value, defaultValue));
    }

    /** Adds a setting whose provenance BootUI cannot determine. */
    FaultToleranceSettings addUnknownProvenance(String name, Object value) {
        return addRendered(name, render(value), FaultToleranceVocabulary.PROVENANCE_UNKNOWN);
    }

    /** Adds a setting that is explicitly configured (an annotation attribute the user wrote). */
    FaultToleranceSettings addConfigured(String name, Object value) {
        return addRendered(name, render(value), FaultToleranceVocabulary.PROVENANCE_CONFIGURED);
    }

    private FaultToleranceSettings addRendered(String name, String value, String provenance) {
        if (value != null) {
            settings.add(new FaultTolerancePolicySettingDto(name, value, provenance));
        }
        return this;
    }

    List<FaultTolerancePolicySettingDto> build() {
        return List.copyOf(settings);
    }

    private static String provenance(Object value, Object defaultValue) {
        if (defaultValue == null) {
            return FaultToleranceVocabulary.PROVENANCE_UNKNOWN;
        }
        return java.util.Objects.equals(String.valueOf(value), String.valueOf(defaultValue))
                ? FaultToleranceVocabulary.PROVENANCE_DEFAULT
                : FaultToleranceVocabulary.PROVENANCE_CONFIGURED;
    }

    /**
     * Renders a value for display. Durations become an explicit millisecond count, floats keep one decimal,
     * and {@code null} is dropped entirely so an unavailable value is omitted rather than shown as "null".
     *
     * <p>A duration shorter than a millisecond keeps a finer unit: Resilience4j's default rate limiter
     * refresh period is 500 nanoseconds, and rounding it to {@code 0 ms} would tell the developer the
     * limiter refreshes instantly.</p>
     */
    static String render(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Duration duration) {
            return renderDuration(duration);
        }
        if (value instanceof Float floatValue) {
            return trimTrailingZero(String.format(java.util.Locale.ROOT, "%.1f", floatValue));
        }
        if (value instanceof Double doubleValue) {
            return trimTrailingZero(String.format(java.util.Locale.ROOT, "%.1f", doubleValue));
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static String renderDuration(Duration duration) {
        int nanos = duration.getNano();
        if (duration.getSeconds() == 0 && nanos != 0 && nanos < 1_000_000) {
            return nanos < 1_000 ? nanos + " ns" : nanos / 1_000 + " \u00b5s";
        }
        return duration.toMillis() + " ms";
    }

    private static String trimTrailingZero(String value) {
        return value.endsWith(".0") ? value.substring(0, value.length() - 2) : value;
    }

    /** Renders a percentage threshold, keeping the unit visible in the UI. */
    static String percent(float value) {
        return render(value) + "%";
    }
}
