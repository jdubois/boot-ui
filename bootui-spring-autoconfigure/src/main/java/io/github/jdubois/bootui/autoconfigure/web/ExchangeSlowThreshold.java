package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * The request slow threshold that BootUI's HTTP exchange repository and its trace side-registry can both honestly
 * apply on Spring: {@code bootui.activity.request-slow-threshold-ms}, or {@code 0} (slow classification off, only
 * failures reserved) when {@code management.httpexchanges.recording.include} leaves out {@code time-taken}, because the
 * recorded exchanges then carry no duration. Resolving it in one place keeps the two buffers classifying the same
 * requests as reserved.
 *
 * <p>It reads the property from the {@link Environment} rather than Actuator's {@code HttpExchangesProperties}, so it
 * is safe to call when Actuator is absent. Unset, Actuator records {@code time-taken} by default.</p>
 */
public final class ExchangeSlowThreshold {

    static final String INCLUDE_PROPERTY = "management.httpexchanges.recording.include";

    private ExchangeSlowThreshold() {}

    public static long resolve(BootUiProperties properties, Environment environment) {
        long threshold = properties.getActivity().getRequestSlowThresholdMs();
        if (environment == null) {
            return threshold;
        }
        List<String> includes = Binder.get(environment)
                .bind(INCLUDE_PROPERTY, Bindable.listOf(String.class))
                .orElse(null);
        if (includes == null) {
            return threshold;
        }
        return includes.stream().anyMatch(ExchangeSlowThreshold::isTimeTaken) ? threshold : 0L;
    }

    /** Matches {@code time-taken} the way Spring binds the {@code Include} enum: ignoring case and separators. */
    private static boolean isTimeTaken(String value) {
        return value != null
                && value.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT).equals("timetaken");
    }
}
