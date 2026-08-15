package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.core.dto.HikariPoolDto;
import io.github.jdubois.bootui.core.dto.HikariPoolsReport;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.ServiceMapReport;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.engine.cache.CacheActivityEvent;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.datasource.ConnectionPoolService;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.servicemap.ServiceMapAssembler;
import io.github.jdubois.bootui.engine.servicemap.ServiceMapSources;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Gathers the Spring-side evidence behind Live Activity's Live Flow map and hands it to the shared,
 * framework-neutral {@link ServiceMapAssembler}.
 *
 * <p>Deliberately identical on the servlet and reactive stacks: it reads only beans both stacks register
 * (the shared HTTP Exchanges controller and the engine's pool service and capture recorders), so Spring MVC
 * and Spring WebFlux produce the same map from the same evidence with no stack-specific branch. That
 * includes the {@link CacheActivityRecorder} — declared once in the shared {@code BootUiEngineConfiguration}
 * for both stacks — so cache dependencies and their opaque flow correlation behave identically on Spring MVC
 * and Spring WebFlux; Quarkus has no equivalent recorder and reports {@code cacheAvailable: false}.</p>
 *
 * <p>This class adds no instrumentation, performs no external call, and never re-masks or re-shapes what a
 * source already produced. Every source is gated on its own panel being enabled <em>and</em> its capture
 * actually able to feed it, so a disabled panel or an uninstrumented recorder contributes nothing
 * rather than appearing as a silent dependency.</p>
 */
public class LiveServiceMapService {

    private final ObjectProvider<HttpExchangesController> httpExchanges;
    private final ObjectProvider<RestClientTraceRecorder> restClientTrace;
    private final ObjectProvider<ConnectionPoolService> connectionPools;
    private final ObjectProvider<SqlTraceRecorder> sqlTrace;
    private final ObjectProvider<KafkaActivityRecorder> kafka;
    private final ObjectProvider<RabbitActivityRecorder> rabbit;
    private final ObjectProvider<CacheActivityRecorder> cacheActivity;
    private final BootUiProperties properties;
    private final BootUiExposure exposure;
    private final Predicate<String> beanTypePresent;
    private final ServiceMapAssembler assembler = new ServiceMapAssembler();

    public LiveServiceMapService(
            ObjectProvider<HttpExchangesController> httpExchanges,
            ObjectProvider<RestClientTraceRecorder> restClientTrace,
            ObjectProvider<ConnectionPoolService> connectionPools,
            ObjectProvider<SqlTraceRecorder> sqlTrace,
            ObjectProvider<KafkaActivityRecorder> kafka,
            ObjectProvider<RabbitActivityRecorder> rabbit,
            ObjectProvider<CacheActivityRecorder> cacheActivity,
            BootUiProperties properties,
            BootUiExposure exposure,
            Predicate<String> beanTypePresent) {
        this.httpExchanges = httpExchanges;
        this.restClientTrace = restClientTrace;
        this.connectionPools = connectionPools;
        this.sqlTrace = sqlTrace;
        this.kafka = kafka;
        this.rabbit = rabbit;
        this.cacheActivity = cacheActivity;
        this.properties = properties;
        this.exposure = exposure;
        this.beanTypePresent = beanTypePresent;
    }

    public ServiceMapReport serviceMap() {
        return assembler.assemble(sources());
    }

    ServiceMapSources sources() {
        List<HttpExchangeDto> inbound = inboundExchanges();
        List<RestClientTraceEntryDto> restCalls = restClientCalls();
        List<HikariPoolDto> pools = jdbcPools();
        SqlTraceRecorder sqlRecorder = sqlRecorder();
        List<SqlTraceEntryDto> statements = sqlRecorder == null
                ? List.of()
                // Parameter bindings are never requested here: the map only ever needs the coarse
                // statement category, so there is nothing to expose even under a permissive policy.
                : sqlRecorder.report(false).entries();
        List<String> tracedDataSources = sqlRecorder == null ? List.of() : sqlRecorder.dataSourceNames();
        KafkaActivityRecorder kafkaRecorder = kafkaRecorder();
        RabbitActivityRecorder rabbitRecorder = rabbitRecorder();
        List<CacheActivityEvent> cacheEvents = cacheEvents();

        return new ServiceMapSources(
                inbound != null,
                inbound == null ? List.of() : inbound,
                restCalls != null,
                restCalls == null ? List.of() : restCalls,
                pools != null,
                pools == null ? List.of() : pools,
                sqlRecorder != null,
                statements,
                tracedDataSources,
                kafkaRecorder != null,
                kafkaRecorder == null ? List.of() : kafkaRecorder.recent(),
                rabbitRecorder != null,
                rabbitRecorder == null ? List.of() : rabbitRecorder.recent(),
                cacheEvents != null,
                cacheEvents == null ? List.of() : cacheEvents);
    }

    /** Completed incoming requests, already self-filtered and masked by the HTTP Exchanges panel. */
    private List<HttpExchangeDto> inboundExchanges() {
        if (!properties.isPanelEnabled(BootUiPanels.HTTP_EXCHANGES)) {
            return null;
        }
        HttpExchangesController controller = httpExchanges.getIfAvailable();
        if (controller == null) {
            return null;
        }
        // Null offset/limit means "the whole retained buffer": the panel's paging helper clamps a literal
        // 0 to a single row, which would silently reduce the inbound lane to one interaction.
        HttpExchangesReport report = controller.exchanges(null, null, null, null, null);
        return report.unavailableReason() != null ? null : report.exchanges();
    }

    /**
     * Outbound HTTP calls. Requires an actually instrumented client, so an application that merely has the
     * panel enabled does not look like it has an empty outbound surface it never had.
     */
    private List<RestClientTraceEntryDto> restClientCalls() {
        if (!properties.isPanelEnabled(BootUiPanels.REST_CLIENT_TRACE)) {
            return null;
        }
        RestClientTraceRecorder recorder = restClientTrace.getIfAvailable();
        if (recorder == null || !recorder.isEnabled() || !recorder.hasInstrumentedClient()) {
            return null;
        }
        return recorder.report(exposure.maskSecrets(), exposure.valueExposure()).entries();
    }

    private List<HikariPoolDto> jdbcPools() {
        if (!properties.isPanelEnabled(BootUiPanels.DATABASE_CONNECTION_POOLS)) {
            return null;
        }
        ConnectionPoolService service = connectionPools.getIfAvailable();
        if (service == null) {
            return null;
        }
        HikariPoolsReport report = service.report();
        return report.hikariPresent() ? report.pools() : null;
    }

    private SqlTraceRecorder sqlRecorder() {
        if (!properties.isPanelEnabled(BootUiPanels.SQL_TRACE)) {
            return null;
        }
        SqlTraceRecorder recorder = sqlTrace.getIfAvailable();
        return recorder != null && recorder.isEnabled() && recorder.hasWrappedDataSource() ? recorder : null;
    }

    private KafkaActivityRecorder kafkaRecorder() {
        if (!properties.isPanelEnabled(BootUiPanels.KAFKA)) {
            return null;
        }
        if (!beanTypePresent.test("org.springframework.kafka.core.KafkaTemplate")) {
            return null;
        }
        KafkaActivityRecorder recorder = kafka.getIfAvailable();
        return recorder != null && recorder.isEnabled() ? recorder : null;
    }

    private RabbitActivityRecorder rabbitRecorder() {
        if (!properties.isPanelEnabled(BootUiPanels.RABBITMQ)) {
            return null;
        }
        if (!beanTypePresent.test("org.springframework.amqp.rabbit.core.RabbitTemplate")) {
            return null;
        }
        RabbitActivityRecorder recorder = rabbit.getIfAvailable();
        return recorder != null && recorder.isEnabled() ? recorder : null;
    }

    /**
     * Captured cache accesses, gathered only when the dedicated Cache panel is enabled <em>and</em> its
     * {@link CacheActivityRecorder} is itself capturing through at least one successfully instrumented
     * manager. Returns {@code null} when any gate is closed, so the assembler reports the source as
     * entirely absent; returns the recorder's own (possibly empty) bounded buffer otherwise, so an
     * instrumented-but-so-far-silent recorder still counts as an available source with no traffic yet.
     */
    private List<CacheActivityEvent> cacheEvents() {
        if (!properties.isPanelEnabled(BootUiPanels.CACHE)) {
            return null;
        }
        CacheActivityRecorder recorder = cacheActivity.getIfAvailable();
        if (recorder == null || !recorder.isEnabled() || !recorder.hasInstrumentedManager()) {
            return null;
        }
        return recorder.recentEvents();
    }
}
