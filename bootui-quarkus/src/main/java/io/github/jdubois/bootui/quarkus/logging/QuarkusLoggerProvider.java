package io.github.jdubois.bootui.quarkus.logging;

import io.github.jdubois.bootui.core.dto.LoggerDto;
import io.github.jdubois.bootui.core.dto.LoggersReport;
import io.github.jdubois.bootui.spi.LoggerProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Quarkus {@link LoggerProvider} backed by the {@code java.util.logging} runtime Quarkus runs on (the
 * JBoss LogManager). It is the Quarkus analogue of the Spring adapter's {@code SpringLoggerProvider}
 * (which sits on Actuator's {@code LoggersEndpoint}); the framework-neutral concerns — self-logger
 * filtering, sorting, paging and the write guard — live in the shared engine {@code LoggersService}, so
 * both adapters share them and this class only returns raw neutral data and performs the backend call.
 *
 * <p>It deliberately uses only the JDK {@code java.util.logging} API (the JBoss LogManager is a
 * {@code java.util.logging.LogManager}), so the extension needs no compile dependency on JBoss
 * internals. Every backend interaction is wrapped fail-soft: any {@link LinkageError} or
 * {@link RuntimeException} degrades to "no backend available" (empty report / rejected write) rather
 * than failing the panel.</p>
 *
 * <p><strong>Level vocabulary.</strong> The shared UI binds to BootUI's canonical levels
 * ({@code OFF, FATAL, ERROR, WARN, INFO, DEBUG, TRACE}). {@link #rawLoggers()} reports them in the same
 * descending-severity order the Spring Actuator endpoint returns, and the JUL {@link Level} of each
 * logger is bucketed onto a canonical name by its {@code intValue()} — a bucketing that maps both the
 * standard JUL levels and the JBoss levels (whose {@code intValue}s coincide with the bucket
 * boundaries: {@code FATAL}=1100, {@code ERROR}=1000, {@code WARN}=900, {@code INFO}=800,
 * {@code DEBUG}=500, {@code TRACE}=400) onto the same names. Writes map the canonical name back onto a
 * JUL {@link Level} chosen so the value reads back as the same canonical name.</p>
 */
public final class QuarkusLoggerProvider implements LoggerProvider {

    /**
     * The canonical level vocabulary in descending severity, matching the order Spring's Actuator
     * {@code LoggersEndpoint} returns (a descending {@code TreeSet} of {@code LogLevel}) so the shared
     * UI's level dropdown renders identically on both platforms.
     */
    static final List<String> LEVELS = List.of("OFF", "FATAL", "ERROR", "WARN", "INFO", "DEBUG", "TRACE");

    /** The JBoss LogManager class-name prefix; the only backend on which this provider activates. */
    private static final String JBOSS_LOG_MANAGER_PREFIX = "org.jboss.logmanager";

    /** Display name BootUI (and Spring's Actuator) uses for the unnamed JUL root logger. */
    private static final String ROOT_DISPLAY_NAME = "ROOT";

    /**
     * A JUL level for {@code FATAL} — {@code java.util.logging} has no standard equivalent. Its
     * {@code intValue} (1100) matches the JBoss {@code FATAL} level, so a value set here reads back as
     * {@code FATAL} through the {@code intValue} bucketing in {@link #canonicalName(Level)}.
     */
    private static final Level FATAL_LEVEL = new Level("FATAL", 1100) {};

    @Override
    public boolean available() {
        try {
            LogManager manager = LogManager.getLogManager();
            return manager != null && manager.getClass().getName().startsWith(JBOSS_LOG_MANAGER_PREFIX);
        } catch (LinkageError | RuntimeException ex) {
            return false;
        }
    }

    @Override
    public LoggersReport rawLoggers() {
        if (!available()) {
            return new LoggersReport(List.of(), List.of());
        }
        try {
            LogManager manager = LogManager.getLogManager();
            List<LoggerDto> loggers = new ArrayList<>();
            for (String name : Collections.list(manager.getLoggerNames())) {
                Logger logger = manager.getLogger(name);
                if (logger == null) {
                    continue;
                }
                loggers.add(new LoggerDto(
                        displayName(name), canonicalName(logger.getLevel()), canonicalName(effectiveLevel(logger))));
            }
            return new LoggersReport(LEVELS, loggers);
        } catch (LinkageError | RuntimeException ex) {
            return new LoggersReport(List.of(), List.of());
        }
    }

    @Override
    public LoggerDto setLevel(String name, String level) {
        if (!available()) {
            throw new IllegalStateException("No logger backend is available");
        }
        Level target = toJulLevel(level);
        String loggerName = ROOT_DISPLAY_NAME.equals(name) ? "" : name;
        Logger logger = Logger.getLogger(loggerName);
        logger.setLevel(target);
        return new LoggerDto(
                displayName(loggerName), canonicalName(logger.getLevel()), canonicalName(effectiveLevel(logger)));
    }

    /** Resolves the effective level by walking the parent chain; falls back to the JUL default INFO. */
    private static Level effectiveLevel(Logger logger) {
        for (Logger current = logger; current != null; current = current.getParent()) {
            Level level = current.getLevel();
            if (level != null) {
                return level;
            }
        }
        return Level.INFO;
    }

    /** Maps a JUL {@link Level} onto BootUI's canonical level name by severity; {@code null} stays null. */
    static String canonicalName(Level level) {
        if (level == null) {
            return null;
        }
        int value = level.intValue();
        if (value >= Level.OFF.intValue()) {
            return "OFF";
        }
        if (value >= 1100) {
            return "FATAL";
        }
        if (value >= Level.SEVERE.intValue()) {
            return "ERROR";
        }
        if (value >= Level.WARNING.intValue()) {
            return "WARN";
        }
        if (value >= Level.INFO.intValue()) {
            return "INFO";
        }
        if (value >= Level.FINE.intValue()) {
            return "DEBUG";
        }
        return "TRACE";
    }

    /**
     * Maps a canonical level name onto a JUL {@link Level} whose {@code intValue} reads back as the same
     * canonical name. A {@code null}/blank name resets the level (inherit from the parent); an
     * unrecognized name throws {@link IllegalArgumentException} (mapped to HTTP 400 by the resource).
     */
    static Level toJulLevel(String level) {
        if (level == null || level.isBlank()) {
            return null;
        }
        return switch (level.trim().toUpperCase(Locale.ROOT)) {
            case "OFF" -> Level.OFF;
            case "FATAL" -> FATAL_LEVEL;
            case "ERROR" -> Level.SEVERE;
            case "WARN" -> Level.WARNING;
            case "INFO" -> Level.INFO;
            case "DEBUG" -> Level.FINE;
            case "TRACE" -> Level.FINER;
            default -> throw new IllegalArgumentException("Unknown log level '" + level + "'");
        };
    }

    private static String displayName(String julName) {
        return julName == null || julName.isEmpty() ? ROOT_DISPLAY_NAME : julName;
    }
}
