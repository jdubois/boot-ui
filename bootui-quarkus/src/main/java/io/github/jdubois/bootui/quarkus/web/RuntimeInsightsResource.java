package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import io.github.jdubois.bootui.engine.insights.AiUsageByRoute;
import io.github.jdubois.bootui.engine.insights.InsightsStack;
import io.github.jdubois.bootui.engine.insights.RunComparisonService;
import io.github.jdubois.bootui.engine.insights.RuntimeInsightsService;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.RunHistory;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.github.jdubois.bootui.spi.MappingProvider;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.Config;

/**
 * The Runtime Insights panel on Quarkus ({@code docs/PLAN-v2.md} §5.5): the same engine projection as the Spring
 * adapters, served at {@code GET /runtime-insights} and {@code GET /runtime-insights/insights/{id}}, with the run
 * comparison at {@code GET /runtime-insights/comparison}. All are pure reads of what the runtime journal and the run
 * history already hold. Quarkus records no transactions, so the observations that place
 * work in transactions report themselves not applicable.
 */
@Path("/bootui/api/runtime-insights")
public class RuntimeInsightsResource {

    private final RuntimeInsightsService insights;
    private final RunComparisonService comparison;

    @Inject
    public RuntimeInsightsResource(
            Instance<RuntimeJournal> journal,
            Instance<JournalAggregates> aggregates,
            QuarkusPanelAvailability panelAvailability,
            Instance<MappingProvider> mappings,
            Config config) {
        JournalAggregates journalAggregates = aggregates.isResolvable() ? aggregates.get() : null;
        this.comparison = new RunComparisonService(
                journal.isResolvable() ? journal.get() : null, journalAggregates, RunHistory.shared());
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

    /** The current run compared with the newest kept run, or with the kept run {@code run} ({@code PLAN-v2} §5.8). */
    @GET
    @Path("/comparison")
    @Produces(MediaType.APPLICATION_JSON)
    public RuntimeRunComparisonDto comparison(@QueryParam("run") String run) {
        return comparison.compare(run);
    }
}
