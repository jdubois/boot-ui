package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.RuntimeChangeImpactDto;
import io.github.jdubois.bootui.core.dto.RuntimeImpactSymbolsDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeResourceProfileDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import io.github.jdubois.bootui.engine.insights.AiUsageByRoute;
import io.github.jdubois.bootui.engine.insights.ChangeImpactService;
import io.github.jdubois.bootui.engine.insights.InsightsStack;
import io.github.jdubois.bootui.engine.insights.ResourceProfileService;
import io.github.jdubois.bootui.engine.insights.RunComparisonService;
import io.github.jdubois.bootui.engine.insights.RuntimeInsightsService;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.RunHistory;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.model.RuntimeModelService;
import io.github.jdubois.bootui.engine.model.StructureSnapshots;
import io.github.jdubois.bootui.engine.resources.ResourceSettings;
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
            Config config) {
        JournalAggregates journalAggregates = aggregates.isResolvable() ? aggregates.get() : null;
        this.comparison = new RunComparisonService(
                journal.isResolvable() ? journal.get() : null, journalAggregates, RunHistory.shared());
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
                journalAggregates == null ? null : journalAggregates.declaredRoutes());
        this.profile = new ResourceProfileService(
                journal.isResolvable() ? journal.get() : null,
                journalAggregates == null ? null : journalAggregates.declaredRoutes(),
                config.getOptionalValue("bootui.resources.jfr.max-duration", Duration.class)
                        .orElse(ResourceSettings.DEFAULT_JFR_MAX_DURATION));
        this.insights = new RuntimeInsightsService(
                journal.isResolvable() ? journal.get() : null,
                journalAggregates == null ? null : journalAggregates.declaredRoutes(),
                panel -> panelAvailability.isPanelAvailable(panel) && panelAvailability.isPanelEnabled(panel),
                InsightsStack.QUARKUS,
                RunHistory.shared()::summaries,
                config.getOptionalValue("bootui.runtime-insights.ai-token-threshold", Long.class)
                        .filter(threshold -> threshold > 0)
                        .orElse(AiUsageByRoute.DEFAULT_TOKEN_THRESHOLD));
        if (journalAggregates != null) {
            this.insights.setDeclaredRoutes(DeclaredRouteTemplates.declared(mappings), journalAggregates::routeLabels);
        }
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
