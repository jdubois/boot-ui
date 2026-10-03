package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
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
import java.util.List;
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
                    sql,
                    exposure,
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
}
