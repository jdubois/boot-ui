package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.engine.insights.AiUsageByRoute;
import io.github.jdubois.bootui.engine.insights.InsightsStack;
import io.github.jdubois.bootui.engine.insights.RuntimeInsightsService;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.RunHistory;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.Config;

/**
 * The Runtime Insights panel on Quarkus ({@code docs/PLAN-v2.md} §5.5): the same engine projection as the Spring
 * adapters, served at {@code GET /runtime-insights} and {@code GET /runtime-insights/insights/{id}}. Both are pure
 * reads of what the runtime journal already recorded. Quarkus records no transactions, so the observations that place
 * work in transactions report themselves not applicable.
 */
@Path("/bootui/api/runtime-insights")
public class RuntimeInsightsResource {

    private final RuntimeInsightsService insights;

    @Inject
    public RuntimeInsightsResource(
            Instance<RuntimeJournal> journal,
            Instance<JournalAggregates> aggregates,
            QuarkusPanelAvailability panelAvailability,
            Config config) {
        JournalAggregates journalAggregates = aggregates.isResolvable() ? aggregates.get() : null;
        this.insights = new RuntimeInsightsService(
                journal.isResolvable() ? journal.get() : null,
                journalAggregates == null ? null : journalAggregates.declaredRoutes(),
                panel -> panelAvailability.isPanelAvailable(panel) && panelAvailability.isPanelEnabled(panel),
                InsightsStack.QUARKUS,
                RunHistory.shared()::summaries,
                config.getOptionalValue("bootui.runtime-insights.ai-token-threshold", Long.class)
                        .filter(threshold -> threshold > 0)
                        .orElse(AiUsageByRoute.DEFAULT_TOKEN_THRESHOLD));
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
}
