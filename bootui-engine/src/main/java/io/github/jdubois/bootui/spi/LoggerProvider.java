package io.github.jdubois.bootui.spi;

import io.github.jdubois.bootui.core.dto.LoggerDto;
import io.github.jdubois.bootui.core.dto.LoggersReport;

/**
 * Framework-neutral seam behind the Loggers panel: it reports the host application's loggers and the
 * level vocabulary, and applies a single level change.
 *
 * <p>The Spring Boot adapter implements this over Actuator's {@code LoggersEndpoint}; the Quarkus
 * adapter implements it over the JBoss LogManager. The engine {@code LoggersService} owns the
 * framework-neutral concerns (self-logger filtering, sorting, paging, and the write guard) so both
 * adapters share them; this provider only returns raw neutral data and performs the backend call.</p>
 *
 * <p>Implementations must return the canonical BootUI level vocabulary ({@code OFF, FATAL, ERROR,
 * WARN, INFO, DEBUG, TRACE}) so the single shared UI renders identically on every framework.</p>
 */
public interface LoggerProvider {

    /**
     * Whether a logging backend is currently available. {@code false} means the backend type is on the
     * classpath but no usable instance exists (for example Actuator present but the loggers endpoint
     * bean is absent); the engine then serves an empty report and rejects level changes.
     */
    boolean available();

    /**
     * The raw, <em>unfiltered</em> and <em>unpaged</em> snapshot: the available level vocabulary plus
     * every logger the backend knows about. The engine applies BootUI's self-logger filter, sorting and
     * paging on top of this. Returns an empty report when {@link #available()} is {@code false}.
     */
    LoggersReport rawLoggers();

    /**
     * Sets (or, when {@code level} is {@code null} or blank, resets) the configured level of one logger
     * and returns its refreshed view. Implementations throw {@link IllegalArgumentException} for an
     * unrecognized level and {@link IllegalStateException} when no backend is available.
     */
    LoggerDto setLevel(String name, String level);
}
