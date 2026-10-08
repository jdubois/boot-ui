package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.RuntimeChangeImpactDto;
import io.github.jdubois.bootui.core.dto.RuntimeImpactSymbolsDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeResourceProfileDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import io.github.jdubois.bootui.engine.codepaths.CodePathsService;
import io.github.jdubois.bootui.engine.insights.ChangeImpactService;
import io.github.jdubois.bootui.engine.insights.InsightsStack;
import io.github.jdubois.bootui.engine.insights.ResourceProfileService;
import io.github.jdubois.bootui.engine.insights.RunComparisonService;
import io.github.jdubois.bootui.engine.insights.RuntimeInsightsService;
import io.github.jdubois.bootui.engine.insights.SqlCapture;
import io.github.jdubois.bootui.engine.insights.UnrecordedWork;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
import io.github.jdubois.bootui.engine.javaagent.AgentHandoffs;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.RunHistory;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.model.RuntimeModelService;
import io.github.jdubois.bootui.engine.model.StructureSnapshots;
import io.github.jdubois.bootui.engine.resources.ResourceSettings;
import io.github.jdubois.bootui.engine.sideeffects.SideEffectsService;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.web.ProfileCapabilities;
import io.github.jdubois.bootui.quarkus.BootUiEngineProducer;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.github.jdubois.bootui.spi.BeanProvider;
import io.github.jdubois.bootui.spi.MappingProvider;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.util.List;
import org.eclipse.microprofile.config.Config;

/**
 * The Runtime Insights panel on Quarkus ({@code docs/PLAN-v2.md} §5.5): the same engine projection as the Spring
 * adapters, served at {@code GET /runtime-insights} and {@code GET /runtime-insights/insights/{id}}, with the run
 * comparison at {@code GET /runtime-insights/comparison}. These are pure reads of what the runtime journal and the run
 * history already hold; only {@code POST /runtime-insights/resource-profile}, which the developer triggers, starts a
 * bounded JFR session. Quarkus records no transactions, so the observations that place
 * work in transactions report themselves not applicable.
 */
@Path("/bootui/api/runtime-insights")
public class RuntimeInsightsResource {

    private final RuntimeInsightsService insights;
    private final RunComparisonService comparison;
    private final ChangeImpactService impact;
    private final ResourceProfileService profile;

    @Inject
    public RuntimeInsightsResource(
            Instance<RuntimeJournal> journal,
            Instance<JournalAggregates> aggregates,
            QuarkusPanelAvailability panelAvailability,
            Instance<MappingProvider> mappings,
            Instance<BeanProvider> beans,
            Instance<JavaAgentService> javaAgent,
            Instance<CodeInventoryService> codeInventory,
            Instance<CodePathsService> codePaths,
            Instance<SideEffectsService> sideEffects,
            Instance<SqlTraceRecorder> sqlTraceRecorder,
            QuarkusExposurePolicy exposure,
            Config config) {
        JournalAggregates journalAggregates = aggregates.isResolvable() ? aggregates.get() : null;
        this.comparison = new RunComparisonService(
                journal.isResolvable() ? journal.get() : null,
                journalAggregates,
                RunHistory.shared(),
                panelAvailability::isPanelEnabled,
                panelAvailability::isPanelAvailable);
        RuntimeModelService models = new RuntimeModelService(
                journal.isResolvable() ? journal.get() : null,
                journalAggregates == null ? null : journalAggregates.declaredRoutes(),
                runId -> StructureSnapshots.read(
                        runId,
                        beans.isResolvable() ? beans.get() : null,
                        mappings.isResolvable() ? mappings.get() : null));
        this.impact = new ChangeImpactService(
                journal.isResolvable() ? journal.get() : null,
                journalAggregates,
                models,
                journalAggregates == null ? null : journalAggregates.declaredRoutes(),
                panel -> panelAvailability.isPanelAvailable(panel) && panelAvailability.isPanelEnabled(panel));
        this.impact.setStack(InsightsStack.QUARKUS);
        this.profile = new ResourceProfileService(
                journal.isResolvable() ? journal.get() : null,
                journalAggregates == null ? null : journalAggregates.declaredRoutes(),
                config.getOptionalValue("bootui.resources.jfr.max-duration", Duration.class)
                        .orElse(ResourceSettings.DEFAULT_JFR_MAX_DURATION),
                panel -> panelAvailability.isPanelAvailable(panel) && panelAvailability.isPanelEnabled(panel));
        this.insights = new RuntimeInsightsService(
                journal.isResolvable() ? journal.get() : null,
                journalAggregates == null ? null : journalAggregates.declaredRoutes(),
                panel -> panelAvailability.isPanelAvailable(panel) && panelAvailability.isPanelEnabled(panel),
                InsightsStack.QUARKUS,
                () -> RunHistory.shared().summaries(journalAggregates == null ? null : journalAggregates.application()),
                BootUiEngineProducer.runtimeInsightsTokenThreshold(config));
        this.insights.setExposure(exposure);
        if (journalAggregates != null) {
            this.insights.setDeclaredRoutes(DeclaredRouteTemplates.declared(mappings), journalAggregates::routeLabels);
        }
        this.insights.setSqlCapture(() -> sqlCapture(sqlTraceRecorder));
        this.insights.setUnrecordedWork(
                UnrecordedWork.detect(Thread.currentThread().getContextClassLoader()));
        // A panel this application cannot serve is not a panel its developer switched off, and Runtime Insights must
        // say which it is: the authorization evidence of an application without Quarkus security events, say.
        this.insights.setPanelUnavailable(panel -> insightsUnavailableReason(panelAvailability, panel));
        JavaAgentService agent = javaAgent.isResolvable() ? javaAgent.get() : null;
        this.insights.setAgent(
                () -> agent == null ? ProfileCapabilities.PROPAGATION_REASON : agent.propagationUnavailableReason(),
                config.getOptionalValue("bootui.agent.executors.max-handoff", Duration.class)
                        .orElse(AgentHandoffs.DEFAULT_MAX_HANDOFF));
        // Read under one read of the Code Inventory and HTTP Exchanges panels per projection (docs/PLAN-v2.md §8,
        // M5-11).
        this.insights.setCodeInventoryService(() -> codeInventory.isResolvable() ? codeInventory.get() : null);
        // route-time-breakdown's handler split by method and repeated-selects' issuing method, from the agent's code
        // paths (docs/PLAN-v2.md §5.14).
        models.setInvocations(
                () -> codePaths.isResolvable() ? codePaths.get().invocations() : List.of(),
                () -> codePaths.isResolvable() ? codePaths.get().routeTreesFingerprint() : 0L);
        // The hosts Side Effects' network sensor saw routes, jobs, and beans open (docs/PLAN-v2.md §5.16, M5-5b).
        models.setHostOpens(
                () -> sideEffects.isResolvable() ? sideEffects.get().hostOpens() : List.of(),
                () -> sideEffects.isResolvable() ? sideEffects.get().hostOpensFingerprint() : 0L);
        // The files and environment variables executions access, from the agent's Side Effects (docs/PLAN-v2.md §5.16).
        models.setSideEffects(
                () -> sideEffects.isResolvable() ? sideEffects.get().modelAccesses() : List.of(),
                () -> sideEffects.isResolvable() ? sideEffects.get().modelFingerprint() : 0L);
        // Change impact by method and the comparison's code changes, from the route trees and Code Inventory (M5-7a).
        this.impact.setCodePaths(
                wanted -> codePaths.isResolvable() ? codePaths.get().methodRoutes(wanted) : null);
        this.impact.setCodeInventory((type, name) ->
                codeInventory.isResolvable() ? codeInventory.get().lookup(type, name) : null);
        // What changed outside the JVM since the previous start, from Side Effects (M5-7b).
        this.comparison.setSideEffects(() -> sideEffects.isResolvable() ? sideEffects.get() : null);
        this.comparison.setCodeChanges(
                () -> codeInventory.isResolvable() && codeInventory.get().agentAttached(),
                limit -> codeInventory.isResolvable() ? codeInventory.get().changesWithAccess(limit) : null,
                wanted -> codePaths.isResolvable() ? codePaths.get().methodRoutes(wanted) : null);
        this.insights.setCodePathsService(() -> codePaths.isResolvable() ? codePaths.get() : null);
    }

    /**
     * Whether this application's SQL is recorded: the SQL Trace recorder exists only with a JDBC (Agroal) data source,
     * whose pool BootUI wraps and whose Hibernate ORM statements its inspector records. Without it, as with Hibernate
     * Reactive or a reactive SQL client, the checks that read SQL are unavailable rather than evaluated over nothing.
     */
    static SqlCapture sqlCapture(Instance<SqlTraceRecorder> recorder) {
        return SqlCapture.of(recorder != null && recorder.isResolvable() ? recorder.get() : null, false, false);
    }

    /**
     * Why {@code panel} is unavailable in this application, as a sentence Runtime Insights can append to its own, or
     * {@code null} when the panel is available. The panel catalogue's reasons open with "Not available:" or "Not
     * applicable on Quarkus:" because they stand alone in the panel shell; Runtime Insights already says which of the
     * two it means, so the opening is dropped rather than repeated.
     */
    static String insightsUnavailableReason(QuarkusPanelAvailability availability, String panel) {
        String reason = availability.panelUnavailableReason(panel);
        if (reason == null) {
            return null;
        }
        for (String opening : new String[] {"Not available:", "Not applicable on Quarkus:", "Not applicable:"}) {
            if (reason.startsWith(opening)) {
                return reason.substring(opening.length()).trim();
            }
        }
        return reason;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public RuntimeInsightsReportDto report() {
        return insights.report();
    }

    @GET
    @Path("/insights/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public RuntimeObservationDetailDto insight(@PathParam("id") String id) {
        return insights.insight(id);
    }

    /** What a change to a bean, class, repository, table, cache, or host reaches in this run ({@code PLAN-v2} §5.7). */
    @GET
    @Path("/impact")
    @Produces(MediaType.APPLICATION_JSON)
    public RuntimeChangeImpactDto impact(@QueryParam("symbol") String symbol) {
        return impact.impact(symbol);
    }

    /** The routes, beans, tables, and other symbols the impact can check that match {@code query}. */
    @GET
    @Path("/impact/symbols")
    @Produces(MediaType.APPLICATION_JSON)
    public RuntimeImpactSymbolsDto impactSymbols(@QueryParam("query") String query) {
        return impact.symbols(query);
    }

    /** The <b>Profile resources</b> session's state and last results ({@code PLAN-v2} §5.11); starts nothing. */
    @GET
    @Path("/resource-profile")
    @Produces(MediaType.APPLICATION_JSON)
    public RuntimeResourceProfileDto resourceProfile() {
        return profile.status();
    }

    /** Starts a bounded <b>Profile resources</b> JFR session, which only the developer does. */
    @POST
    @Path("/resource-profile")
    @Produces(MediaType.APPLICATION_JSON)
    public RuntimeResourceProfileDto startResourceProfile() {
        return profile.start();
    }

    /** Ends the running <b>Profile resources</b> session now and returns its results. */
    @POST
    @Path("/resource-profile/stop")
    @Produces(MediaType.APPLICATION_JSON)
    public RuntimeResourceProfileDto stopResourceProfile() {
        return profile.stop();
    }

    /** The current run compared with the newest kept run, or with the kept run {@code run} ({@code PLAN-v2} §5.8). */
    @GET
    @Path("/comparison")
    @Produces(MediaType.APPLICATION_JSON)
    public RuntimeRunComparisonDto comparison(@QueryParam("run") String run) {
        return comparison.compare(run);
    }
}
