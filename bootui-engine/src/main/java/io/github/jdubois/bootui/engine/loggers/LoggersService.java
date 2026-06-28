package io.github.jdubois.bootui.engine.loggers;

import io.github.jdubois.bootui.core.dto.LoggerDto;
import io.github.jdubois.bootui.core.dto.LoggersReport;
import io.github.jdubois.bootui.engine.support.PagedList;
import io.github.jdubois.bootui.spi.LoggerProvider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Framework-neutral logic behind the Loggers panel, shared by the Spring Boot and Quarkus adapters.
 *
 * <p>It reads the raw logger snapshot from a {@link LoggerProvider} (optional: {@code null} when the
 * backend type is absent) and applies BootUI's self-logger filtering, name sorting and server-side
 * paging for reads, and a fail-closed write guard for level changes.</p>
 *
 * <p>Two predicates are intentionally distinct (see the read/write split): {@code readVisible} decides
 * which loggers are <em>shown</em> (it honors the operator's self-data preference), while
 * {@code writeBlocked} decides which loggers may never be <em>mutated</em> (BootUI's own loggers,
 * regardless of that preference) so a read toggle can never unlock a write.</p>
 */
public final class LoggersService {

    private final LoggerProvider provider;

    private final Predicate<String> readVisible;

    private final Predicate<String> writeBlocked;

    public LoggersService(LoggerProvider provider, Predicate<String> readVisible, Predicate<String> writeBlocked) {
        this.provider = provider;
        this.readVisible = readVisible;
        this.writeBlocked = writeBlocked;
    }

    /** The filtered, sorted and paged logger report; empty when no backend is available. */
    public LoggersReport report(String query, Integer offset, Integer limit) {
        if (provider == null || !provider.available()) {
            return new LoggersReport(List.of(), List.of());
        }
        LoggersReport snapshot = provider.rawLoggers();
        List<String> levels = snapshot.availableLevels() == null ? List.of() : snapshot.availableLevels();
        List<LoggerDto> loggers = new ArrayList<>();
        for (LoggerDto logger : snapshot.loggers()) {
            if (readVisible.test(logger.name())) {
                loggers.add(logger);
            }
        }
        loggers.sort(Comparator.comparing(LoggerDto::name));
        String normalizedQuery = PagedList.normalize(query);
        PagedList.Result<LoggerDto> page =
                PagedList.from(loggers, logger -> PagedList.contains(logger.name(), normalizedQuery), offset, limit);
        return new LoggersReport(levels, page.items(), page.page());
    }

    /**
     * Sets or resets one logger's level. Rejects changes to BootUI's own loggers with
     * {@link IllegalArgumentException} (mapped to 400) before touching the backend, and throws
     * {@link IllegalStateException} when no backend is available.
     */
    public LoggerDto setLevel(String name, String level) {
        if (name != null && writeBlocked.test(name)) {
            throw new IllegalArgumentException("Refusing to change the level of BootUI's own logger '" + name + "'.");
        }
        if (provider == null || !provider.available()) {
            throw new IllegalStateException("No logger backend is available");
        }
        return provider.setLevel(name, level);
    }
}
