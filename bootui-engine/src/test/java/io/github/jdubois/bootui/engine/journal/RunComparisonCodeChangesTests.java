package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.CodeInventoryChangeCountsDto;
import io.github.jdubois.bootui.core.dto.CodeInventoryChangesReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryMethodDto;
import io.github.jdubois.bootui.core.dto.PageMetadata;
import io.github.jdubois.bootui.core.dto.RuntimeCodeChangeDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonAgentDto;
import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import io.github.jdubois.bootui.engine.codepaths.MethodRoutes;
import io.github.jdubois.bootui.engine.codepaths.TracedMethods;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.insights.RunComparisonService;
import io.github.jdubois.bootui.engine.insights.RuntimeInsightsAgentView;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * M5-7a: the run comparison leads with the methods changed since the previous run, whether each ran, and the routes
 * whose requests' call trees ran it; without the agent it is unchanged and says code changes need it. In this package
 * for {@link RunHistory}'s constructor.
 */
class RunComparisonCodeChangesTests {

    private static final RuntimeJournalSettings SETTINGS =
            new RuntimeJournalSettings(true, 100, 1_000_000, 100, 10, 10, JournalSource.all());
    private static final String CHANGED = "com.example.OrderService#total(J)J";
    private static final String ADDED = "com.example.OrderService#discount()V";
    private static final String HELPER = "com.example.OrderService#round(J)J";

    private final RunHistory history = new RunHistory(5, RunHistory.MAX_SUMMARY_BYTES, null);
    private final RunIdentity older = RunIdentity.start();
    private final RunIdentity newest = RunIdentity.start();
    private final RuntimeJournal journal = new RuntimeJournal(SETTINGS, RunIdentity.start());

    RunComparisonCodeChangesTests() {
        history.record(RunSummary.of(older, new JournalAggregates().snapshot(), 1));
        history.record(RunSummary.of(newest, new JournalAggregates().snapshot(), 2));
    }

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void withoutTheAgentTheComparisonIsUnchangedAndSaysCodeChangesNeedIt() {
        RunComparisonService service = new RunComparisonService(journal, new JournalAggregates(), history);
        RuntimeRunComparisonDto comparison = service.compare(null);

        assertThat(comparison.codeChanges().available()).isFalse();
        assertThat(comparison.codeChanges().unavailableReason()).isEqualTo(RuntimeRunComparisonDto.NO_CODE_CHANGES);
        assertThat(comparison.previous().runId()).isEqualTo(newest.id());
        assertThat(RuntimeInsightsAgentView.comparison(comparison).codeChanges().available())
                .isFalse();
    }

    @Test
    void codeChangesLeadWithEachMethodExecutedOrNotAndTheRoutesWhoseCallTreesRanIt() {
        RunComparisonService service = new RunComparisonService(journal, new JournalAggregates(), history);
        service.setCodeChanges(
                limit -> report(
                        method(CHANGED, "CHANGED", CodeInventoryService.EXECUTED),
                        method(ADDED, "ADDED", CodeInventoryService.NEVER_EXECUTED),
                        method(HELPER, "CHANGED", CodeInventoryService.EXECUTED)),
                keys -> Map.of(CHANGED, 0x0001, ADDED, 0x0001, HELPER, 0x0002),
                wanted -> new MethodRoutes(
                        null,
                        Map.of(CHANGED, Map.of("GET /api/orders", 4L, "GET /api/orders/{id}", 1L)),
                        Map.of(),
                        Map.of(),
                        Set.of(),
                        Set.of("com.example.OrderService"),
                        List.of()));

        RuntimeRunComparisonDto comparison = service.compare(null);

        assertThat(comparison.codeChanges().available()).isTrue();
        assertThat(comparison.codeChanges().counts().removed()).isEqualTo(3);
        assertThat(comparison.codeChanges().methodsTotal()).isEqualTo(3);
        assertThat(comparison.codeChanges().methods())
                .extracting(RuntimeCodeChangeDto::key, RuntimeCodeChangeDto::status, RuntimeCodeChangeDto::routes)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                CHANGED,
                                CodeInventoryService.EXECUTED,
                                List.of("GET /api/orders", "GET /api/orders/{id}")),
                        org.assertj.core.groups.Tuple.tuple(ADDED, CodeInventoryService.NEVER_EXECUTED, List.of()),
                        org.assertj.core.groups.Tuple.tuple(HELPER, CodeInventoryService.EXECUTED, List.of()));
        assertThat(comparison.codeChanges().methods().get(2).routesNote()).startsWith(TracedMethods.NOT_TRACED);
        assertThat(comparison.codeChanges().methods().get(1).routesNote()).isNull();
        assertThat(comparison.codeChanges().limitations())
                .anySatisfy(limitation -> assertThat(limitation).contains("3 removed methods are counted, not named"));

        assertThat(service.compare(older.id()).codeChanges().unavailableReason())
                .as("an older run is not what Code Inventory compared with")
                .contains("previous run only");
        assertThat(service.compare(newest.id()).codeChanges().available()).isTrue();
    }

    @Test
    void theAgentViewCapsTheMethodsAndADisabledPanelLeavesThemOut() {
        List<CodeInventoryMethodDto> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(method("com.example.A#m" + i + "()V", "ADDED", CodeInventoryService.NEVER_EXECUTED));
        }
        RunComparisonService service = new RunComparisonService(
                journal, new JournalAggregates(), history, panel -> !BootUiPanels.CODE_PATHS.equals(panel));
        service.setCodeChanges(
                limit -> report(many.toArray(CodeInventoryMethodDto[]::new)),
                keys -> Map.of(),
                wanted -> MethodRoutes.unavailable("never read"));

        RuntimeRunComparisonAgentDto agent = RuntimeInsightsAgentView.comparison(service.compare("previous"));
        assertThat(agent.codeChanges().methods()).hasSize(RuntimeRunComparisonAgentDto.MAX_ROWS);
        assertThat(agent.codeChanges().methodsTotal()).isEqualTo(12);
        assertThat(agent.codeChanges().limitations())
                .anySatisfy(limitation -> assertThat(limitation).contains("Code Paths panel is disabled"));

        RunComparisonService hidden = new RunComparisonService(
                journal, new JournalAggregates(), history, panel -> !BootUiPanels.CODE_INVENTORY.equals(panel));
        hidden.setCodeChanges(limit -> report(), keys -> Map.of(), null);
        assertThat(hidden.compare(null).codeChanges().unavailableReason()).contains("Code Inventory panel is disabled");

        RunComparisonService failing = new RunComparisonService(journal, new JournalAggregates(), history);
        failing.setCodeChanges(
                limit -> {
                    throw new IllegalStateException("boom");
                },
                keys -> Map.of(),
                null);
        assertThat(failing.compare(null).codeChanges().unavailableReason())
                .startsWith("Code changes could not be read");
    }

    private static CodeInventoryChangesReport report(CodeInventoryMethodDto... methods) {
        return new CodeInventoryChangesReport(
                true,
                null,
                new CodeInventoryChangeCountsDto(true, null, 2, 1, 3, 2, 1, false, "COMPLETE"),
                List.of(methods),
                new PageMetadata(methods.length, methods.length, 0, 200, methods.length, false));
    }

    private static CodeInventoryMethodDto method(String key, String change, String status) {
        int hash = key.indexOf('#');
        int open = key.indexOf('(');
        String className = key.substring(0, hash);
        return new CodeInventoryMethodDto(
                key,
                className.substring(0, className.lastIndexOf('.')),
                className,
                key.substring(hash + 1, open),
                key.substring(open),
                status,
                null,
                change,
                null,
                null,
                null);
    }
}
