package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.HikariPoolDto;
import io.github.jdubois.bootui.core.dto.HikariPoolsReport;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.ServiceMapReport;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.engine.datasource.ConnectionPoolService;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.servicemap.ServiceMapAssembler;
import io.github.jdubois.bootui.engine.servicemap.ServiceMapSources;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.engine.web.HttpExchangesService;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Quarkus JAX-RS binding for Live Activity's Live Flow map ({@code GET /bootui/api/activity/service-map}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code LiveServiceMapController}: a thin transport adapter
 * that gathers evidence from beans it already owns and delegates every interpretation rule to the shared,
 * framework-neutral {@link ServiceMapAssembler}, so all three runtimes serve a byte-identical contract.</p>
 *
 * <p>It lives under the {@code /activity} path because Live Flow is a mode of the Live Activity panel, which
 * also means the shared panel enable/read-only policy and the Vert.x safety filter already cover it. The
 * endpoint is strictly read-only and performs no network call, probe, or scan.</p>
 *
 * <p><strong>Cache is honestly reported as unavailable here.</strong> Quarkus has no {@code
 * CacheActivityRecorder}-equivalent bean at all — {@code quarkus-cache}'s built-in interceptors cast the
 * resolved cache to an internal, non-public type, leaving no comparable interception seam (see
 * {@code docs/QUARKUS-SUPPORT.md}), exactly as for the Live Activity feed's {@code CACHE} entry type. Rather
 * than inventing a substitute capture path, {@link #sources()} always passes {@code cacheAvailable: false}
 * with an empty event list, so this adapter's map never draws a cache dependency it cannot honestly back with
 * evidence.</p>
 */
@Path("/bootui/api/activity")
public class LiveServiceMapResource {

    private final HttpExchangeBuffer buffer;
    private final QuarkusExposurePolicy exposure;
    private final QuarkusPanelAvailability panelAvailability;
    private final SelfTelemetryClassifier selfClassifier;
    private final ConnectionPoolService connectionPools;
    private final Instance<SqlTraceRecorder> sqlRecorder;
    private final RestClientTraceRecorder restClientTraceRecorder;
    private final KafkaActivityRecorder kafkaRecorder;
    private final RabbitActivityRecorder rabbitRecorder;
    private final HttpExchangesService exchanges = new HttpExchangesService();
    private final ServiceMapAssembler assembler = new ServiceMapAssembler();

    @Inject
    public LiveServiceMapResource(
            HttpExchangeBuffer buffer,
            QuarkusExposurePolicy exposure,
            QuarkusPanelAvailability panelAvailability,
            SelfTelemetryClassifier selfClassifier,
            ConnectionPoolService connectionPools,
            Instance<SqlTraceRecorder> sqlRecorder,
            RestClientTraceRecorder restClientTraceRecorder,
            KafkaActivityRecorder kafkaRecorder,
            RabbitActivityRecorder rabbitRecorder) {
        this.buffer = buffer;
        this.exposure = exposure;
        this.panelAvailability = panelAvailability;
        this.selfClassifier = selfClassifier;
        this.connectionPools = connectionPools;
        this.sqlRecorder = sqlRecorder;
        this.restClientTraceRecorder = restClientTraceRecorder;
        this.kafkaRecorder = kafkaRecorder;
        this.rabbitRecorder = rabbitRecorder;
    }

    @GET
    @Path("/service-map")
    @Produces(MediaType.APPLICATION_JSON)
    public ServiceMapReport serviceMap() {
        return assembler.assemble(sources());
    }

    ServiceMapSources sources() {
        List<HttpExchangeDto> inbound = inboundExchanges();
        List<RestClientTraceEntryDto> restCalls = restClientCalls();
        List<HikariPoolDto> pools = jdbcPools();
        SqlTraceRecorder sql = sqlTraceRecorder();
        // Parameter bindings are never requested: the map only carries the coarse statement category.
        List<SqlTraceEntryDto> statements =
                sql == null ? List.of() : sql.report(false).entries();
        List<String> tracedDataSources = sql == null ? List.of() : sql.dataSourceNames();
        boolean kafkaAvailable = messagingAvailable(BootUiPanels.KAFKA) && kafkaRecorder.isEnabled();
        boolean rabbitAvailable = messagingAvailable(BootUiPanels.RABBITMQ) && rabbitRecorder.isEnabled();

        return new ServiceMapSources(
                inbound != null,
                inbound == null ? List.of() : inbound,
                restCalls != null,
                restCalls == null ? List.of() : restCalls,
                pools != null,
                pools == null ? List.of() : pools,
                sql != null,
                statements,
                tracedDataSources,
                kafkaAvailable,
                kafkaAvailable ? kafkaRecorder.recent() : List.of(),
                rabbitAvailable,
                rabbitAvailable ? rabbitRecorder.recent() : List.of(),
                // Quarkus never captures cache accesses (see this class's javadoc): no invented evidence.
                false,
                List.of());
    }

    /** Completed incoming requests, self-filtered and masked exactly as the HTTP Exchanges panel serves them. */
    private List<HttpExchangeDto> inboundExchanges() {
        if (!usable(BootUiPanels.HTTP_EXCHANGES)) {
            return null;
        }
        HttpExchangesReport report = exchanges.report(
                buffer.snapshot(),
                uri -> !selfClassifier.shouldInclude(selfClassifier.isBootUiPath(uri)),
                exposure.maskSecrets(),
                exposure.valueExposure(),
                null,
                null,
                null,
                null,
                null);
        return report.unavailableReason() != null ? null : report.exchanges();
    }

    private List<RestClientTraceEntryDto> restClientCalls() {
        if (!usable(BootUiPanels.REST_CLIENT_TRACE)
                || !restClientTraceRecorder.isEnabled()
                || !restClientTraceRecorder.hasInstrumentedClient()) {
            return null;
        }
        return restClientTraceRecorder
                .report(exposure.maskSecrets(), exposure.valueExposure())
                .entries();
    }

    private List<HikariPoolDto> jdbcPools() {
        if (!usable(BootUiPanels.DATABASE_CONNECTION_POOLS)) {
            return null;
        }
        HikariPoolsReport report = connectionPools.report();
        return report.hikariPresent() ? report.pools() : null;
    }

    private SqlTraceRecorder sqlTraceRecorder() {
        if (!usable(BootUiPanels.SQL_TRACE) || !sqlRecorder.isResolvable()) {
            return null;
        }
        SqlTraceRecorder recorder = sqlRecorder.get();
        return recorder.isEnabled() && recorder.hasWrappedDataSource() ? recorder : null;
    }

    private boolean messagingAvailable(String panelId) {
        return usable(panelId);
    }

    private boolean usable(String panelId) {
        return panelAvailability.isPanelAvailable(panelId) && panelAvailability.isPanelEnabled(panelId);
    }
}
