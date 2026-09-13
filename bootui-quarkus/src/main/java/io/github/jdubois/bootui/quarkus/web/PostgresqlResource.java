package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.PostgresInsightReport;
import io.github.jdubois.bootui.engine.postgres.PostgresInsightService;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the PostgreSQL panel ({@code GET /bootui/api/postgresql},
 * {@code POST /bootui/api/postgresql/read}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code PostgresqlController}: a thin transport adapter
 * over the shared engine {@link PostgresInsightService}, which asks every discovered PostgreSQL
 * {@code DataSource} for its own read-only "vital signs" ({@code pg_stat_*}/{@code pg_catalog} views) under a
 * bounded, single-flighted read. {@code GET} returns the last report (initially "not read");
 * {@code POST /read} performs the read and caches the result. This is a strictly read-only panel: unlike the
 * Database Advisor there is no dismissal store to apply.</p>
 *
 * <p>The resource is produced <em>unconditionally</em> and the engine {@code PostgresInsightService} is always
 * wired (it holds no {@code io.agroal}/JDBC-vendor types): when no {@code DataSource} bean is present the read
 * renders a NOT_READ/empty report rather than failing. The panel's honest availability (JDBC datasource
 * present) is decided by {@code QuarkusPanelAvailability}.</p>
 *
 * <p>It is {@code @ApplicationScoped} (not the default per-request scope) because it caches the last report
 * in a {@code volatile} field across requests — the CDI analogue of the Spring controller's singleton with a
 * {@code volatile lastReport}. Only {@code POST /read} is {@code @Blocking}: it opens JDBC connections and
 * runs several catalog/statistics queries per datasource, which must not run on the Vert.x event loop. The
 * {@code GET} returns the cached field and stays on the event loop.</p>
 */
@ApplicationScoped
@Path("/bootui/api/postgresql")
public class PostgresqlResource {

    private final PostgresInsightService service;

    private volatile PostgresInsightReport lastReport;

    @Inject
    public PostgresqlResource(PostgresInsightService service) {
        this.service = service;
        this.lastReport = service.initialReport();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public PostgresInsightReport postgresql() {
        return lastReport;
    }

    @POST
    @Path("/read")
    @Blocking
    @Produces(MediaType.APPLICATION_JSON)
    public PostgresInsightReport read() {
        PostgresInsightReport report = service.read();
        lastReport = report;
        return report;
    }
}
