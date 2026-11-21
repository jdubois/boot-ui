package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.codepaths.CodePathsService;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.journal.DurableJournalCapture;
import io.github.jdubois.bootui.engine.journal.DurableJournalIdentity;
import io.github.jdubois.bootui.engine.journal.DurableJournalPage;
import io.github.jdubois.bootui.engine.journal.DurableJournalRun;
import io.github.jdubois.bootui.engine.journal.DurableJournalRunMetadata;
import io.github.jdubois.bootui.engine.journal.DurableJournalRuntime;
import io.github.jdubois.bootui.engine.journal.DurableJournalScope;
import io.github.jdubois.bootui.engine.journal.DurableJournalSettings;
import io.github.jdubois.bootui.engine.journal.DurableJournalStore;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalEvidenceSnapshot;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.github.jdubois.bootui.spi.BeanProvider;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.MappingProvider;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.enterprise.inject.Instance;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class RuntimeInsightsResourceComparisonTests {
    private AutoCloseable mocks;

    @Mock
    Instance<RuntimeJournal> journals;

    @Mock
    Instance<JournalAggregates> aggregates;

    @Mock
    Instance<MappingProvider> mappings;

    @Mock
    Instance<BeanProvider> beans;

    @Mock
    Instance<JavaAgentService> agents;

    @Mock
    Instance<CodeInventoryService> inventory;

    @Mock
    Instance<CodePathsService> codePaths;

    @Mock
    Instance<io.github.jdubois.bootui.engine.sideeffects.SideEffectsService> sideEffects;

    @Mock
    Instance<SqlTraceRecorder> sql;

    @Mock
    QuarkusPanelAvailability panels;

    @Mock
    QuarkusExposurePolicy exposure;

    @BeforeEach
    void openMocks() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void closeMocks() throws Exception {
        mocks.close();
    }

    @Test
    void comparisonDistinguishesDisabledAndUnavailableSourcePanelsAndReadsLivePolicy() {
        RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 100, 1_000_000, 100, 10, 10, JournalSource.all()),
                RunIdentity.start());
        try {
            JournalAggregates captured = new JournalAggregates();
            RuntimeEvent request = RuntimeEvent.of(
                    JournalSource.HTTP,
                    1_000,
                    1_000_000,
                    CorrelationContext.forRequest("captured"),
                    "worker",
                    null,
                    false,
                    new HttpPayload("GET", "/private", "/private", null, 200));
            captured.onEntries(List.of(new JournalEntry(1, request, request.estimatedBytes())));
            when(journals.isResolvable()).thenReturn(true);
            when(journals.get()).thenReturn(journal);
            when(aggregates.isResolvable()).thenReturn(true);
            when(aggregates.get()).thenReturn(captured);
            when(panels.isPanelEnabled(anyString())).thenReturn(true);
            when(panels.isPanelAvailable(anyString())).thenReturn(true);
            RuntimeInsightsResource resource = new RuntimeInsightsResource(
                    journals,
                    aggregates,
                    panels,
                    mappings,
                    beans,
                    agents,
                    inventory,
                    codePaths,
                    sideEffects,
                    sql,
                    exposure,
                    new io.github.jdubois.bootui.quarkus.logging.QuarkusLogCoverage(),
                    new SmallRyeConfigBuilder().build());

            assertThat(resource.comparison(null).current().requests()).isEqualTo(1);
            when(panels.isPanelEnabled(BootUiPanels.HTTP_EXCHANGES)).thenReturn(false);
            assertThat(resource.comparison(null).current().requests()).isZero();
            assertThat(resource.comparison(null).limitations())
                    .contains("Facts are not compared because http-exchanges is disabled.");
            when(panels.isPanelEnabled(BootUiPanels.HTTP_EXCHANGES)).thenReturn(true);
            when(panels.isPanelAvailable(BootUiPanels.HTTP_EXCHANGES)).thenReturn(false);
            assertThat(resource.comparison(null).current().requests()).isZero();
            assertThat(resource.comparison(null).limitations())
                    .contains("Facts are not compared because http-exchanges is unavailable.");
            when(panels.isPanelAvailable(BootUiPanels.HTTP_EXCHANGES)).thenReturn(true);
            assertThat(resource.comparison(null).current().requests()).isEqualTo(1);
        } finally {
            journal.close();
        }
    }

    @Test
    void selectedHistoricalReportUsesDurableEvidenceAndEnforcesNamespacePolicy() throws Exception {
        RuntimeJournal current = new RuntimeJournal(RuntimeJournalSettings.defaults(), RunIdentity.start());
        RuntimeJournal old = new RuntimeJournal(RuntimeJournalSettings.defaults(), RunIdentity.start());
        try {
            old.offer(RuntimeEvent.of(
                    JournalSource.HTTP,
                    1_000,
                    1_000_000,
                    CorrelationContext.forRequest("historical"),
                    "worker",
                    null,
                    false,
                    new HttpPayload("GET", "/historical", "/historical", null, 200)));
            assertThat(old.awaitDrained(Duration.ofSeconds(5))).isTrue();
            JournalEvidenceSnapshot snapshot = JournalEvidenceSnapshot.hot(old);
            DurableJournalSettings settings = new DurableJournalSettings(
                    new DurableJournalIdentity(
                            "local",
                            "orders",
                            current.run().id(),
                            "process",
                            current.status().instanceId()),
                    true,
                    Duration.ofSeconds(1),
                    100,
                    1024 * 1024,
                    100,
                    "bootui_activity",
                    Duration.ofDays(7),
                    false);
            DurableJournalScope scope = new DurableJournalScope(
                    settings.namespaceId(), settings.applicationId(), old.run().id());
            DurableJournalRunMetadata metadata = DurableJournalRunMetadata.snapshot(
                    DurableJournalRunMetadata.Phase.CHECKPOINT,
                    old,
                    new DurableJournalIdentity(
                            scope.namespace(),
                            scope.application(),
                            scope.run(),
                            "process",
                            snapshot.status().instanceId()),
                    io.github.jdubois.bootui.engine.insights.InsightsStack.QUARKUS,
                    Map.of(),
                    null,
                    null);
            DurableJournalStore store = mock(DurableJournalStore.class);
            when(store.findRun(scope))
                    .thenReturn(new DurableJournalRun(
                            scope,
                            1,
                            1,
                            "RUNNING",
                            null,
                            old.lastSequence(),
                            snapshot.status().instanceId(),
                            metadata));
            when(store.findRunMetadata(scope)).thenReturn(metadata);
            when(store.query(any())).thenReturn(new DurableJournalPage(snapshot.entries(), null, false));
            when(store.listDetails(any(), anyLong(), anyInt())).thenReturn(List.of());
            DurableJournalRuntime runtime = new DurableJournalRuntime();
            assertThat(runtime.activate(store, mock(DurableJournalCapture.class)))
                    .isTrue();
            when(journals.isResolvable()).thenReturn(true);
            when(journals.get()).thenReturn(current);
            when(panels.isPanelEnabled(anyString())).thenReturn(true);
            when(panels.isPanelAvailable(anyString())).thenReturn(true);
            RuntimeInsightsResource resource = new RuntimeInsightsResource(
                    journals,
                    aggregates,
                    panels,
                    mappings,
                    beans,
                    agents,
                    inventory,
                    codePaths,
                    sideEffects,
                    sql,
                    exposure,
                    new io.github.jdubois.bootui.quarkus.logging.QuarkusLogCoverage(),
                    new SmallRyeConfigBuilder().build());
            resource.setDurableJournal(runtime, settings);

            assertThat(resource.report(scope.namespace(), scope.application(), scope.run()))
                    .satisfies(report -> {
                        assertThat(report.available()).isTrue();
                        assertThat(report.window().runId()).isEqualTo(old.run().id());
                        assertThat(report.window().requests()).isOne();
                    });
            assertThat(resource.report(scope.namespace(), scope.application(), "missing-run")
                            .available())
                    .isFalse();
            assertThatThrownBy(() -> resource.report("other-namespace", scope.application(), scope.run()))
                    .isInstanceOf(jakarta.ws.rs.BadRequestException.class);
        } finally {
            current.close();
            old.close();
        }
    }
}
