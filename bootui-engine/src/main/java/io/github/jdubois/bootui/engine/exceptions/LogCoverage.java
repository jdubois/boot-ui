package io.github.jdubois.bootui.engine.exceptions;

import java.util.List;

/**
 * Whether BootUI sees every {@code WARN}+ log the application writes ({@code docs/PLAN-v2.md} M5-6): only then can a
 * caught exception be called "not logged". Each adapter reads its logging backend: Logback on Spring, the JBoss
 * LogManager on Quarkus. A gap is global, never per handler: a handler can log through any logger.
 */
public interface LogCoverage {

    /** Coverage that cannot be read: every window has a gap. */
    LogCoverage UNREADABLE = new LogCoverage() {
        @Override
        public String gap(long fromMillis, long toMillis) {
            return "BootUI cannot read the application's logging configuration";
        }

        @Override
        public List<String> bypassingLoggers() {
            return List.of();
        }
    };

    /**
     * Why BootUI may not have seen every {@code WARN}+ log written between {@code fromMillis} and {@code toMillis}, by
     * the wall clock, or {@code null} when it saw them all: its appender or handler is not attached to the root logger,
     * the logging configuration was reset in the window, or a {@code WARN}-enabled logger does not pass its events to
     * the root logger.
     */
    String gap(long fromMillis, long toMillis);

    /** The {@code WARN}-enabled loggers that do not pass their events to the root logger, by name, bounded. */
    List<String> bypassingLoggers();
}
