package io.github.jdubois.bootui.autoconfigure.insights;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.DeclaredRouteTemplates;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import io.github.jdubois.bootui.engine.insights.InsightsStack;
import io.github.jdubois.bootui.engine.insights.RunComparisonService;
import io.github.jdubois.bootui.engine.insights.RuntimeInsightsService;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.RunHistory;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.spi.MappingProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.context.reactive.ReactiveWebApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Runtime Insights panel ({@code docs/PLAN-v2.md} §5.5), shared by the Spring MVC and WebFlux adapters:
 * {@code GET /runtime-insights} projects the runtime journal's retained events into observations, and
 * {@code GET /runtime-insights/insights/{id}} returns one observation's evidence, and
 * {@code GET /runtime-insights/comparison} compares the current run with a kept one. All are pure reads of what the
 * journal and the run history already hold: they start no capture, scan, database read, or network call. On WebFlux, BootUI's handler
 * adapter runs them off the event loop.
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/runtime-insights")
public class RuntimeInsightsController {

    private final RuntimeInsightsService insights;
    private final RunComparisonService comparison;

    public RuntimeInsightsController(
            ApplicationContext context,
            BootUiProperties properties,
            ObjectProvider<RuntimeJournal> journal,
            ObjectProvider<JournalAggregates> aggregates,
            ObjectProvider<MappingProvider> mappings) {
        JournalAggregates journalAggregates = aggregates.getIfAvailable();
        this.comparison = new RunComparisonService(journal.getIfAvailable(), journalAggregates, RunHistory.shared());
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
        this.insights.setProxyBoundaries(new SpringProxyBoundaries(context));
        if (journalAggregates != null) {
            this.insights.setDeclaredRoutes(DeclaredRouteTemplates.declared(mappings), journalAggregates::routeLabels);
        }
    }

    @GetMapping
    public RuntimeInsightsReportDto report() {
        return insights.report();
    }

    @GetMapping("/insights/{id}")
    public RuntimeObservationDetailDto insight(@PathVariable String id) {
        return insights.insight(id);
    }

    /** The current run compared with the newest kept run, or with the kept run {@code run} ({@code PLAN-v2} §5.8). */
    @GetMapping("/comparison")
    public RuntimeRunComparisonDto comparison(@RequestParam(name = "run", required = false) String run) {
        return comparison.compare(run);
    }
}
