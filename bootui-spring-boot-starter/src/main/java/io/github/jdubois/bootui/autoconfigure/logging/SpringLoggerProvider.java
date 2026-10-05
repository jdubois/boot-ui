package io.github.jdubois.bootui.autoconfigure.logging;

import io.github.jdubois.bootui.core.dto.LoggerDto;
import io.github.jdubois.bootui.core.dto.LoggersReport;
import io.github.jdubois.bootui.spi.LoggerProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.actuate.logging.LoggersEndpoint;
import org.springframework.boot.actuate.logging.LoggersEndpoint.LoggerLevelsDescriptor;
import org.springframework.boot.actuate.logging.LoggersEndpoint.LoggersDescriptor;
import org.springframework.boot.actuate.logging.LoggersEndpoint.SingleLoggerLevelsDescriptor;
import org.springframework.boot.logging.LogLevel;

/**
 * Spring Boot {@link LoggerProvider} backed by Actuator's {@link LoggersEndpoint}.
 *
 * <p>This class is the single touch-point for the Actuator logging types, and it is only instantiated
 * inside the {@code @ConditionalOnClass} nested configuration in {@code BootUiEngineConfiguration}, so
 * the {@link LoggersEndpoint} type is never linked in an Actuator-absent application. The endpoint is
 * resolved <em>live</em> through a supplier because the endpoint bean may be absent (Actuator present
 * but the loggers endpoint not enabled), in which case the provider reports itself unavailable and the
 * engine serves an empty report.</p>
 */
public final class SpringLoggerProvider implements LoggerProvider {

    private final Supplier<LoggersEndpoint> endpoint;

    public SpringLoggerProvider(Supplier<LoggersEndpoint> endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public boolean available() {
        return endpoint.get() != null;
    }

    @Override
    public LoggersReport rawLoggers() {
        LoggersEndpoint le = endpoint.get();
        if (le == null) {
            return new LoggersReport(List.of(), List.of());
        }
        LoggersDescriptor descriptor = le.loggers();
        List<String> levels = new ArrayList<>();
        if (descriptor.getLevels() != null) {
            for (LogLevel level : descriptor.getLevels()) {
                levels.add(level.name());
            }
        }
        List<LoggerDto> loggers = new ArrayList<>();
        Map<String, LoggerLevelsDescriptor> map = descriptor.getLoggers();
        if (map != null) {
            for (Map.Entry<String, LoggerLevelsDescriptor> entry : map.entrySet()) {
                loggers.add(toDto(entry.getKey(), entry.getValue()));
            }
        }
        return new LoggersReport(levels, loggers);
    }

    @Override
    public LoggerDto setLevel(String name, String level) {
        LoggersEndpoint le = endpoint.get();
        if (le == null) {
            throw new IllegalStateException("No logger backend is available");
        }
        LogLevel parsed = level == null || level.isBlank() ? null : LogLevel.valueOf(level.toUpperCase(Locale.ROOT));
        le.configureLogLevel(name, parsed);
        return toDto(name, le.loggerLevels(name));
    }

    private LoggerDto toDto(String name, LoggerLevelsDescriptor descriptor) {
        String configured = descriptor.getConfiguredLevel();
        String effective = configured;
        if (descriptor instanceof SingleLoggerLevelsDescriptor single) {
            effective = single.getEffectiveLevel();
        }
        return new LoggerDto(name, configured, effective);
    }
}
