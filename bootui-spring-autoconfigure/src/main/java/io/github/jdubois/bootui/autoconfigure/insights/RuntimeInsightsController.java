package io.github.jdubois.bootui.autoconfigure.insights;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.engine.insights.InsightsStack;
import io.github.jdubois.bootui.engine.insights.RuntimeInsightsService;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.RunHistory;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.context.reactive.ReactiveWebApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Runtime Insights panel ({@code docs/PLAN-v2.md} §5.5), shared by the Spring MVC and WebFlux adapters:
 * {@code GET /runtime-insights} projects the runtime journal's retained events into observations, and
 * {@code GET /runtime-insights/insights/{id}} returns one observation's evidence. Both are pure reads of what the
 * journal already recorded: they start no capture, scan, database read, or network call. On WebFlux, BootUI's handler
 * adapter runs them off the event loop.
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/runtime-insights")
public class RuntimeInsightsController {

    private final RuntimeInsightsService insights;

    public RuntimeInsightsController(
            ApplicationContext context,
            BootUiProperties properties,
            ObjectProvider<RuntimeJournal> journal,
            ObjectProvider<JournalAggregates> aggregates) {
        JournalAggregates journalAggregates = aggregates.getIfAvailable();
        this.insights = new RuntimeInsightsService(
                journal.getIfAvailable(),
                journalAggregates == null ? null : journalAggregates.declaredRoutes(),
                properties::isPanelEnabled,
                context instanceof ReactiveWebApplicationContext
                        ? InsightsStack.SPRING_WEBFLUX
                        : InsightsStack.SPRING_MVC,
                RunHistory.shared()::summaries,
                properties.getRuntimeInsights().getAiTokenThreshold());
        this.insights.setPoolSizes(new DataSourcePoolSizes(context));
    }

    @GetMapping
    public RuntimeInsightsReportDto report() {
        return insights.report();
    }

    @GetMapping("/insights/{id}")
    public RuntimeObservationDetailDto insight(@PathVariable String id) {
        return insights.insight(id);
    }
}
