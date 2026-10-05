package io.github.jdubois.bootui.autoconfigure.postgres;

import io.github.jdubois.bootui.core.dto.PostgresInsightReport;
import io.github.jdubois.bootui.engine.postgres.PostgresInsightService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the PostgreSQL panel.
 *
 * <p>{@code GET} returns the last report (initially "not read"); {@code POST /read} performs a bounded,
 * on-demand, read-only "vital signs" read of the application's own PostgreSQL database through its
 * {@code pg_stat_*} and {@code pg_catalog} views and caches the result. The read is user-triggered: it never
 * runs on page render, never writes, and each read is bounded by row counts and a wall-clock budget on the
 * database session. The read logic lives in the engine {@link PostgresInsightService} (single-flighted on
 * {@code ActionOperations.POSTGRESQL_READ}); this controller only caches the last report.</p>
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/postgresql")
public class PostgresqlController {

    private final PostgresInsightService service;

    private volatile PostgresInsightReport lastReport;

    public PostgresqlController(PostgresInsightService service) {
        this.service = service;
        this.lastReport = service.initialReport();
    }

    @GetMapping
    public PostgresInsightReport postgresql() {
        return lastReport;
    }

    @PostMapping("/read")
    public PostgresInsightReport read() {
        PostgresInsightReport report = service.read();
        lastReport = report;
        return report;
    }
}
