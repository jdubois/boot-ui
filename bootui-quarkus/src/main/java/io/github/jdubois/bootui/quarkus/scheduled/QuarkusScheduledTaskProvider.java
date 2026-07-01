package io.github.jdubois.bootui.quarkus.scheduled;

import io.github.jdubois.bootui.core.dto.ScheduledTaskDto;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTimeUnits;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.spi.ScheduledTaskProvider;
import io.quarkus.runtime.configuration.DurationConverter;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.Config;

/**
 * Quarkus {@link ScheduledTaskProvider} backed by the build-time-captured {@link QuarkusScheduledTasks} holder.
 *
 * <p>The deployment processor exposes the synthetic {@code QuarkusScheduledTasks} bean only when the
 * {@code quarkus-scheduler} capability is present and the launch mode is non-production. This provider is
 * therefore wired unconditionally but tolerates the bean's absence: an unsatisfied {@code Instance} means no
 * scheduler is on the classpath, so {@link #available()} is {@code false} and the engine renders the panel
 * with {@code schedulingPresent=false}. When the holder is present (even with zero tasks) the panel is
 * available.</p>
 *
 * <p>The raw {@code @Scheduled} strings are resolved and parsed here, at request time, not at build time:
 * Quarkus config references ({@code "{prop}"} / {@code "${prop}"}) only have a value at runtime, and routing
 * durations/cron through a config object (rather than a config key) keeps cron spaces+commas and
 * {@code ${...}} sequences intact. Mapping mirrors the Quarkus {@code SimpleScheduler} initial-delay
 * semantics: {@code delay > 0} (in {@code delayUnit}, default {@code MINUTES}) takes precedence over
 * {@code delayed}, and the resulting initial delay applies to both cron and {@code every} triggers. A
 * non-blank {@code cron} yields a {@code CRON} row; otherwise a non-blank {@code every} yields a
 * {@code FIXED_RATE} row. BootUI's own scheduled methods are filtered out via the shared engine
 * {@link InternalPackageMatcher}.</p>
 */
@Singleton
public class QuarkusScheduledTaskProvider implements ScheduledTaskProvider {

    private static final InternalPackageMatcher INTERNAL_PACKAGES =
            new InternalPackageMatcher(List.of("io.github.jdubois.bootui.quarkus", "io.github.jdubois.bootui.core"));

    private final Instance<QuarkusScheduledTasks> capturedTasks;

    private final Config config;

    @Inject
    public QuarkusScheduledTaskProvider(Instance<QuarkusScheduledTasks> capturedTasks, Config config) {
        this.capturedTasks = capturedTasks;
        this.config = config;
    }

    @Override
    public boolean available() {
        return !capturedTasks.isUnsatisfied();
    }

    @Override
    public List<ScheduledTaskDto> tasks() {
        if (capturedTasks.isUnsatisfied()) {
            return List.of();
        }
        return capturedTasks.get().tasks().stream()
                .map(this::toDto)
                .filter(Objects::nonNull)
                .filter(task -> task.runnable() == null || !INTERNAL_PACKAGES.matchesName(task.runnable()))
                .toList();
    }

    private ScheduledTaskDto toDto(RawScheduledTask raw) {
        Long initialDelayMs = initialDelay(raw);
        String cron = resolve(raw.cron());
        if (!cron.isBlank()) {
            String timeUnit = initialDelayMs == null ? null : ScheduledTimeUnits.intervalUnit(null, initialDelayMs);
            return new ScheduledTaskDto(raw.methodDescription(), "CRON", cron, initialDelayMs, timeUnit);
        }
        String every = resolve(raw.every());
        if (!every.isBlank()) {
            Long everyMs = parseMillis(every);
            if (everyMs != null) {
                return new ScheduledTaskDto(
                        raw.methodDescription(),
                        "FIXED_RATE",
                        Long.toString(everyMs),
                        initialDelayMs,
                        ScheduledTimeUnits.intervalUnit(everyMs, initialDelayMs));
            }
            // Unparseable or unresolved config reference: surface the raw expression. The Vue view falls back
            // to rendering it verbatim (Number(expression) is NaN), so the row stays informative.
            return new ScheduledTaskDto(raw.methodDescription(), "FIXED_RATE", every, initialDelayMs, null);
        }
        // Neither cron nor every — not a valid @Scheduled trigger; emit no row.
        return null;
    }

    private Long initialDelay(RawScheduledTask raw) {
        if (raw.delay() > 0) {
            return delayUnit(raw.delayUnit()).toMillis(raw.delay());
        }
        String delayed = resolve(raw.delayed());
        if (!delayed.isBlank()) {
            return parseMillis(delayed);
        }
        return null;
    }

    private static TimeUnit delayUnit(String name) {
        if (name == null || name.isBlank()) {
            return TimeUnit.MINUTES; // @Scheduled.delayUnit default
        }
        try {
            return TimeUnit.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return TimeUnit.MINUTES;
        }
    }

    private Long parseMillis(String value) {
        if (value == null || value.isBlank() || isOff(value)) {
            return null;
        }
        try {
            return DurationConverter.parseDuration(value).toMillis();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean isOff(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("off") || normalized.equals("disabled");
    }

    /**
     * Best-effort resolution of a Quarkus config reference in a {@code @Scheduled} member, mirroring
     * {@code SchedulerUtils.lookUpPropertyValue}: a {@code "{property}"} shorthand or a
     * {@code "${property[:default]}"} expression is resolved against MicroProfile {@link Config}; anything
     * else (including a reference whose property is absent) is returned with leading whitespace stripped, so
     * duration/cron parsing sees the bare token. Never throws — an unresolved reference is rendered raw.
     */
    private String resolve(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.stripLeading();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            String inner = trimmed.substring(2, trimmed.length() - 1);
            int colon = inner.indexOf(':');
            String key = colon >= 0 ? inner.substring(0, colon) : inner;
            String fallback = colon >= 0 ? inner.substring(colon + 1) : trimmed;
            return config.getOptionalValue(key, String.class).orElse(fallback);
        }
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            String key = trimmed.substring(1, trimmed.length() - 1);
            return config.getOptionalValue(key, String.class).orElse(trimmed);
        }
        return trimmed;
    }
}
