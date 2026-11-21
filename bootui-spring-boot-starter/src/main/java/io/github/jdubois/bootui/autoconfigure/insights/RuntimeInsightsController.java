package io.github.jdubois.bootui.autoconfigure.insights;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.javaagent.AgentPropagation;
import io.github.jdubois.bootui.autoconfigure.journal.SpringAppEventCapture;
import io.github.jdubois.bootui.autoconfigure.web.DeclaredRouteTemplates;
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
import io.github.jdubois.bootui.engine.insights.UnrecordedWork;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.journal.DurableJournalArchiveService;
import io.github.jdubois.bootui.engine.journal.DurableJournalEvidence;
import io.github.jdubois.bootui.engine.journal.DurableJournalRuntime;
import io.github.jdubois.bootui.engine.journal.DurableJournalScope;
import io.github.jdubois.bootui.engine.journal.DurableJournalSettings;
import io.github.jdubois.bootui.engine.journal.DurableJournalStoreException;
import io.github.jdubois.bootui.engine.journal.DurableJournalUnavailableException;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalEvidenceSnapshot;
import io.github.jdubois.bootui.engine.journal.RunHistory;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.model.RuntimeModelService;
import io.github.jdubois.bootui.engine.model.StructureSnapshots;
import io.github.jdubois.bootui.engine.sideeffects.SideEffectsService;
import io.github.jdubois.bootui.spi.BeanProvider;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.MappingProvider;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.context.reactive.ReactiveWebApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Runtime Insights panel ({@code docs/PLAN-v2.md} §5.5), shared by the Spring MVC and WebFlux adapters:
 * {@code GET /runtime-insights} projects the runtime journal's retained events into observations, and
 * {@code GET /runtime-insights/insights/{id}} returns one observation's evidence, and
 * {@code GET /runtime-insights/comparison} compares the current run with a kept one. These are pure reads of what the
 * journal and the run history already hold: they start no capture, scan, database read, or network call. Only {@code
 * POST /runtime-insights/resource-profile}, which the developer triggers, starts a bounded JFR session. On WebFlux, BootUI's handler
 * adapter runs them off the event loop.
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/runtime-insights")
public class RuntimeInsightsController {

    private final RuntimeInsightsService insights;
    private final RunComparisonService comparison;
    private final ChangeImpactService impact;
    private final ResourceProfileService profile;
    private final RuntimeJournal durableJournal;
    private final DurableJournalRuntime durableRuntime;
    private final DurableJournalSettings durableSettings;
    private final ExposurePolicy historicalExposure;

    public RuntimeInsightsController(
            ApplicationContext context,
            BootUiProperties properties,
            ObjectProvider<RuntimeJournal> journal,
            ObjectProvider<JournalAggregates> aggregates,
            ObjectProvider<MappingProvider> mappings,
            ObjectProvider<BeanProvider> beans) {
        JournalAggregates journalAggregates = aggregates.getIfAvailable();
        this.comparison = new RunComparisonService(
                journal.getIfAvailable(), journalAggregates, RunHistory.shared(), properties::isPanelEnabled);
        this.durableJournal = journal.getIfAvailable();
        this.durableRuntime =
                context.getBeanProvider(DurableJournalRuntime.class).getIfAvailable();
        this.durableSettings =
                context.getBeanProvider(DurableJournalSettings.class).getIfAvailable();
        RuntimeModelService models = new RuntimeModelService(
                journal.getIfAvailable(),
                journalAggregates == null ? null : journalAggregates.declaredRoutes(),
                runId -> StructureSnapshots.read(runId, beans.getIfUnique(), mappings.getIfUnique()));
        this.impact = new ChangeImpactService(
                journal.getIfAvailable(),
                journalAggregates,
                models,
                journalAggregates == null ? null : journalAggregates.declaredRoutes(),
                properties::isPanelEnabled);
        this.impact.setStack(
                context instanceof ReactiveWebApplicationContext
                        ? InsightsStack.SPRING_WEBFLUX
                        : InsightsStack.SPRING_MVC);
        this.profile = new ResourceProfileService(
                journal.getIfAvailable(),
                journalAggregates == null ? null : journalAggregates.declaredRoutes(),
                properties.getResources().toSettings().jfrMaxDuration(),
                properties::isPanelEnabled);
        this.insights = new RuntimeInsightsService(
                journal.getIfAvailable(),
                journalAggregates == null ? null : journalAggregates.declaredRoutes(),
                properties::isPanelEnabled,
                context instanceof ReactiveWebApplicationContext
                        ? InsightsStack.SPRING_WEBFLUX
                        : InsightsStack.SPRING_MVC,
                () -> RunHistory.shared().summaries(journalAggregates == null ? null : journalAggregates.application()),
                properties.getRuntimeInsights().getAiTokenThreshold());
        this.insights.setPoolSizes(new DataSourcePoolSizes(context));
        this.insights.setSqlCapture(new SpringSqlCapture(context));
        // Without BootUI's event multicaster, as beside Spring Modulith's, no application event is recorded (M4-8).
        SpringAppEventCapture appEvents = new SpringAppEventCapture(context);
        this.insights.setAppEventCapture(appEvents);
        this.impact.setAppEventCapture(appEvents);
        this.comparison.setAppEventCapture(appEvents);
        this.insights.setUnrecordedWork(UnrecordedWork.detect(context.getClassLoader()));
        // The live policy, so a runtime change of bootui.expose-values applies to the next read (PLAN-v2 §8).
        BootUiExposure exposure = context.getBeanProvider(BootUiExposure.class).getIfAvailable();
        this.historicalExposure = exposure != null ? exposure : new BootUiExposure(properties);
        this.insights.setExposure(historicalExposure);
        this.insights.setProxyBoundaries(new SpringProxyBoundaries(context));
        if (journalAggregates != null) {
            this.insights.setDeclaredRoutes(DeclaredRouteTemplates.declared(mappings), journalAggregates::routeLabels);
        }
        ObjectProvider<JavaAgentService> javaAgent = context.getBeanProvider(JavaAgentService.class);
        this.insights.setAgent(
                () -> AgentPropagation.unavailableReason(javaAgent),
                properties.getAgent().getExecutors().getMaxHandoff());
        ObjectProvider<CodeInventoryService> codeInventory = context.getBeanProvider(CodeInventoryService.class);
        // Read under one read of the Code Inventory and HTTP Exchanges panels per projection (docs/PLAN-v2.md §8,
        // M5-11).
        this.insights.setCodeInventoryService(codeInventory::getIfUnique);
        // route-time-breakdown's handler split by method and repeated-selects' issuing method, from the agent's code
        // paths (docs/PLAN-v2.md §5.14).
        ObjectProvider<CodePathsService> codePaths = context.getBeanProvider(CodePathsService.class);
        models.setInvocations(
                () -> {
                    CodePathsService paths = codePaths.getIfUnique();
                    return paths == null ? List.of() : paths.invocations();
                },
                () -> {
                    CodePathsService paths = codePaths.getIfUnique();
                    return paths == null ? 0L : paths.routeTreesFingerprint();
                });
        // The hosts Side Effects' network sensor saw routes, jobs, and beans open (docs/PLAN-v2.md §5.16, M5-5b).
        ObjectProvider<SideEffectsService> sideEffects = context.getBeanProvider(SideEffectsService.class);
        models.setHostOpens(
                () -> {
                    SideEffectsService effects = sideEffects.getIfUnique();
                    return effects == null ? List.of() : effects.hostOpens();
                },
                () -> {
                    SideEffectsService effects = sideEffects.getIfUnique();
                    return effects == null ? 0L : effects.hostOpensFingerprint();
                });
        // The files and environment variables executions access, from the agent's Side Effects (docs/PLAN-v2.md §5.16).
        models.setSideEffects(
                () -> {
                    SideEffectsService service = sideEffects.getIfUnique();
                    return service == null ? List.of() : service.modelAccesses();
                },
                () -> {
                    SideEffectsService service = sideEffects.getIfUnique();
                    return service == null ? 0L : service.modelFingerprint();
                });
        // Change impact by method and the comparison's code changes, from the route trees and Code Inventory (M5-7a).
        this.impact.setCodePaths(wanted -> {
            CodePathsService paths = codePaths.getIfUnique();
            return paths == null ? null : paths.methodRoutes(wanted);
        });
        this.impact.setCodeInventory((type, name) -> {
            CodeInventoryService inventory = codeInventory.getIfUnique();
            return inventory == null ? null : inventory.lookup(type, name);
        });
        // What changed outside the JVM since the previous run, from Side Effects (M5-7b).
        this.comparison.setSideEffects(sideEffects::getIfUnique);
        this.comparison.setCodeChanges(
                () -> {
                    CodeInventoryService inventory = codeInventory.getIfUnique();
                    return inventory != null && inventory.agentAttached();
                },
                limit -> {
                    CodeInventoryService inventory = codeInventory.getIfUnique();
                    return inventory == null ? null : inventory.changesWithAccess(limit);
                },
                wanted -> {
                    CodePathsService paths = codePaths.getIfUnique();
                    return paths == null ? null : paths.methodRoutes(wanted);
                });
        this.insights.setCodePathsService(codePaths::getIfUnique);
    }

    @GetMapping
    public RuntimeInsightsReportDto report(
            @RequestParam(name = "namespace", required = false) String namespace,
            @RequestParam(name = "application", required = false) String application,
            @RequestParam(name = "run", required = false) String run) {
        try {
            DurableJournalScope historical = historicalScope(namespace, application, run);
            if (historical != null) {
                return archive().insights(historical);
            }
            JournalEvidenceSnapshot evidence = durableEvidence(namespace, application, run);
            return evidence == null ? insights.report() : insights.reportFrom(evidence);
        } catch (DurableJournalUnavailableException ex) {
            return new RuntimeInsightsReportDto(
                    false, ex.getMessage(), null, List.of(), List.of(), List.of(), List.of(), List.of(), 0);
        } catch (DurableJournalStoreException ex) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Runtime Insights durable evidence is unavailable", ex);
        }
    }

    public RuntimeInsightsReportDto report() {
        return report(null, null, null);
    }

    @GetMapping("/insights/{id}")
    public RuntimeObservationDetailDto insight(
            @PathVariable String id,
            @RequestParam(name = "namespace", required = false) String namespace,
            @RequestParam(name = "application", required = false) String application,
            @RequestParam(name = "run", required = false) String run) {
        try {
            DurableJournalScope historical = historicalScope(namespace, application, run);
            if (historical != null) {
                return archive().insight(historical, id);
            }
            JournalEvidenceSnapshot evidence = durableEvidence(namespace, application, run);
            return evidence == null ? insights.insight(id) : insights.insightFrom(evidence, id);
        } catch (DurableJournalUnavailableException ex) {
            return new RuntimeObservationDetailDto(false, ex.getMessage(), null, List.of(), List.of(), 0);
        } catch (DurableJournalStoreException ex) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Runtime Insights durable evidence is unavailable", ex);
        }
    }

    public RuntimeObservationDetailDto insight(String id) {
        return insight(id, null, null, null);
    }

    private JournalEvidenceSnapshot durableEvidence(String namespace, String application, String run) {
        boolean requested = hasText(namespace) || hasText(application) || hasText(run);
        if (durableSettings == null) {
            if (requested) {
                throw new DurableJournalUnavailableException("Durable journal archive settings are unavailable");
            }
            return null;
        }
        DurableJournalScope selected = selectedScope(namespace, application, run);
        boolean historical = !selected.equals(durableSettings.scope());
        boolean needsArchive =
                historical || durableJournal != null && durableJournal.status().offloadedEvents() > 0;
        if (!needsArchive) {
            return null;
        }
        if (durableRuntime == null || !durableRuntime.active()) {
            throw new DurableJournalUnavailableException("Durable journal evidence is unavailable for " + selected);
        }
        DurableJournalEvidence evidence = new DurableJournalEvidence(durableRuntime.store());
        return historical ? evidence.read(selected) : evidence.read(durableJournal, selected);
    }

    private DurableJournalScope historicalScope(String namespace, String application, String run) {
        boolean requested = hasText(namespace) || hasText(application) || hasText(run);
        if (durableSettings == null) {
            if (requested) {
                throw new DurableJournalUnavailableException("Durable journal archive settings are unavailable");
            }
            return null;
        }
        DurableJournalScope selected = selectedScope(namespace, application, run);
        return selected.equals(durableSettings.scope()) ? null : selected;
    }

    private DurableJournalArchiveService archive() {
        if (durableRuntime == null || !durableRuntime.active()) {
            throw new DurableJournalUnavailableException("Durable journal evidence is unavailable");
        }
        return durableSettings.readerArchiveCrossNamespace()
                ? DurableJournalArchiveService.crossNamespaceReader(durableRuntime.store(), historicalExposure)
                : DurableJournalArchiveService.forNamespace(
                        durableRuntime.store(), historicalExposure, durableSettings.namespaceId());
    }

    private DurableJournalScope selectedScope(String namespace, String application, String run) {
        String selectedNamespace = valueOr(namespace, durableSettings.namespaceId());
        String selectedApplication = valueOr(application, durableSettings.applicationId());
        String selectedRun = valueOr(run, durableSettings.runId());
        if (!selectedNamespace.equals(durableSettings.namespaceId())
                && !durableSettings.readerArchiveCrossNamespace()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Durable journal namespace selection is disabled by configuration");
        }
        try {
            return new DurableJournalScope(selectedNamespace, selectedApplication, selectedRun);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid durable journal scope", ex);
        }
    }

    private static String valueOr(String value, String fallback) {
        return hasText(value) ? value.strip() : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * What a change to a bean, class, method, repository, table, cache, or host reaches in this run ({@code PLAN-v2}
     * §5.7):
     * the routes that ran through it, those that did not, and those that share a resource with it.
     */
    @GetMapping("/impact")
    public RuntimeChangeImpactDto impact(@RequestParam(name = "symbol", required = false) String symbol) {
        return impact.impact(symbol);
    }

    /** The routes, beans, tables, and other symbols the impact can check that match {@code query}. */
    @GetMapping("/impact/symbols")
    public RuntimeImpactSymbolsDto impactSymbols(@RequestParam(name = "query", required = false) String query) {
        return impact.symbols(query);
    }

    /** The <b>Profile resources</b> session's state and last results ({@code PLAN-v2} §5.11); starts nothing. */
    @GetMapping("/resource-profile")
    public RuntimeResourceProfileDto resourceProfile() {
        return profile.status();
    }

    /** Starts a bounded <b>Profile resources</b> JFR session, which only the developer does. */
    @PostMapping("/resource-profile")
    public RuntimeResourceProfileDto startResourceProfile() {
        return profile.start();
    }

    /** Ends the running <b>Profile resources</b> session now and returns its results. */
    @PostMapping("/resource-profile/stop")
    public RuntimeResourceProfileDto stopResourceProfile() {
        return profile.stop();
    }

    /** The current run compared with the newest kept run, or with the kept run {@code run} ({@code PLAN-v2} §5.8). */
    @GetMapping("/comparison")
    public RuntimeRunComparisonDto comparison(@RequestParam(name = "run", required = false) String run) {
        return comparison.compare(run);
    }
}
