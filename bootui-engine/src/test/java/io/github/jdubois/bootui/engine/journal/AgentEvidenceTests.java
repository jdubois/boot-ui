package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeAgentEvidenceDto;
import io.github.jdubois.bootui.core.dto.RuntimeJournalClearRequest;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentEvidenceTests {

    private final Set<String> hidden = new HashSet<>();

    /** A store that records its clears. */
    private static final class FakeStore implements AgentEvidence.Store {

        final String id;
        final String panel;
        final List<Long> clears = new ArrayList<>();
        long bytes = 1_000;
        boolean fail;
        String unavailable;

        FakeStore(String id, String panel) {
            this.id = id;
            this.panel = panel;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String panel() {
            return panel;
        }

        @Override
        public String title() {
            return "Code Paths";
        }

        @Override
        public String unavailableReason() {
            return unavailable;
        }

        @Override
        public AgentEvidence.Usage usage() {
            if (fail) {
                throw new IllegalStateException("boom");
            }
            return new AgentEvidence.Usage(bytes, 2_000, Map.of("requestTrees", 3L));
        }

        @Override
        public String clear(long epochMillis) {
            if (fail) {
                throw new IllegalStateException("boom");
            }
            clears.add(epochMillis);
            return "3 trees of " + id;
        }
    }

    @Test
    void aReadResolvesTheStoresPanelAndHttpExchangesOnceAndFailsClosed() {
        assertThat(AgentEvidence.Read.of(BootUiPanels.CODE_PATHS, "Code Paths", panel -> true))
                .isEqualTo(AgentEvidence.Read.open(BootUiPanels.CODE_PATHS));

        AgentEvidence.Read noRequests = AgentEvidence.Read.of(
                BootUiPanels.CODE_PATHS, "Code Paths", panel -> !panel.equals(BootUiPanels.HTTP_EXCHANGES));
        assertThat(noRequests.shown()).isTrue();
        assertThat(noRequests.requests()).isFalse();
        assertThat(noRequests.hiddenReason()).isNull();

        AgentEvidence.Read hiddenPanel = AgentEvidence.Read.of(
                BootUiPanels.CODE_PATHS, "Code Paths", panel -> !panel.equals(BootUiPanels.CODE_PATHS));
        assertThat(hiddenPanel.shown()).isFalse();
        assertThat(hiddenPanel.requests()).as("never shown without its panel").isFalse();
        assertThat(hiddenPanel.hiddenReason()).isEqualTo("The Code Paths panel is disabled.");

        AgentEvidence.Read failing = AgentEvidence.Read.of(BootUiPanels.CODE_PATHS, "Code Paths", panel -> {
            throw new IllegalStateException("boom");
        });
        assertThat(failing.shown()).isFalse();
        assertThat(failing.requests()).isFalse();

        assertThat(List.of(AgentEvidence.Read.open("p").key(), noRequests.key(), hiddenPanel.key()))
                .doesNotHaveDuplicates();
    }

    @Test
    void theDefaultBoundKeepsTodaysCapsAndASmallerOneScalesOnlyTheScalableParts() {
        AgentEvidence defaults = AgentEvidence.open();
        assertThat(defaults.maxBytes()).isEqualTo(AgentEvidence.defaultMaxBytes());
        assertThat(defaults.scale()).isEqualTo(1.0);
        assertThat(defaults.scaled(100_000, 10)).isEqualTo(100_000);
        assertThat(AgentEvidence.open()).as("never a shared instance").isNotSameAs(defaults);

        long fixed = AgentEvidence.Part.CODE_INVENTORY_RECORDS.ceilingBytes()
                + AgentEvidence.Part.METHOD_PROBES.ceilingBytes();
        long scalable = AgentEvidence.Part.CODE_PATHS_REQUEST_TREES.ceilingBytes()
                + AgentEvidence.Part.CODE_PATHS_ROUTE_TREES.ceilingBytes();
        AgentEvidence half = new AgentEvidence(null, fixed + scalable / 2);
        assertThat(half.scale()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.001));
        assertThat(half.scaled(100_000, 10)).isCloseTo(50_000, org.assertj.core.data.Offset.offset(10));

        AgentEvidence tiny = new AgentEvidence(null, 1L);
        assertThat(tiny.scale()).isEqualTo(AgentEvidence.MIN_SCALE);
        assertThat(tiny.scaled(100, 16)).isEqualTo(16);
        assertThat(new AgentEvidence(null, 10L * AgentEvidence.defaultMaxBytes()).scale())
                .as("a larger bound never raises the caps")
                .isEqualTo(1.0);
    }

    @Test
    void statusCountsHiddenStoresBytesInTheTotalButShowsNeitherTheirBytesNorCounts() {
        AgentEvidence evidence = new AgentEvidence(panel -> !hidden.contains(panel), 4_096L);
        assertThat(evidence.status()).as("no store registered").isNull();
        FakeStore paths = new FakeStore("code-paths", BootUiPanels.CODE_PATHS);
        FakeStore inventory = new FakeStore("code-inventory", BootUiPanels.CODE_INVENTORY);
        evidence.register(paths);
        evidence.register(inventory);
        evidence.register(paths);
        hidden.add(BootUiPanels.CODE_INVENTORY);

        RuntimeAgentEvidenceDto status = evidence.status();

        assertThat(status.retainedBytes()).isEqualTo(2_000);
        assertThat(status.maxBytes()).as("what the stores hold at most").isEqualTo(4_000);
        assertThat(status.stores()).hasSize(2);
        assertThat(status.stores().get(0).visible()).isTrue();
        assertThat(status.stores().get(0).retainedBytes()).isEqualTo(1_000);
        assertThat(status.stores().get(0).counts()).containsEntry("requestTrees", 3L);
        assertThat(status.stores().get(1).visible()).isFalse();
        assertThat(status.stores().get(1).note()).isEqualTo("The Code Paths panel is disabled.");
        assertThat(status.stores().get(1).retainedBytes()).isNull();
        assertThat(status.stores().get(1).counts()).isEmpty();

        hidden.clear();
        inventory.fail = true;
        assertThat(evidence.status().stores().get(1).note()).isEqualTo("Its usage could not be read.");

        paths.unavailable = "Requires the BootUI agent's code-paths sensor.";
        assertThat(evidence.status().stores())
                .as("a store without the agent is left out, never shown as a disabled panel")
                .extracting(store -> store.store())
                .containsExactly("code-inventory");
        inventory.unavailable = "Requires the BootUI agent's inventory sensor.";
        assertThat(evidence.status())
                .as("no store records: as without the agent")
                .isNull();
    }

    @Test
    void clearClearsEveryStoreIsolatesAFailingOneAndNamesOnlyVisibleEvidence() {
        AgentEvidence evidence = new AgentEvidence(panel -> !hidden.contains(panel), null, () -> 42L);
        FakeStore failing = new FakeStore("failing", BootUiPanels.CODE_PATHS);
        failing.fail = true;
        FakeStore paths = new FakeStore("code-paths", BootUiPanels.CODE_PATHS);
        FakeStore inventory = new FakeStore("code-inventory", BootUiPanels.CODE_INVENTORY);
        evidence.register(failing);
        evidence.register(paths);
        evidence.register(inventory);
        hidden.add(BootUiPanels.CODE_INVENTORY);

        assertThat(evidence.clear()).isEqualTo("3 trees of code-paths");
        assertThat(paths.clears).containsExactly(42L);
        assertThat(inventory.clears).as("cleared though hidden").containsExactly(42L);
        assertThat(evidence.clears()).isEqualTo(1);
        assertThat(evidence.lastCleared()).isEqualTo("3 trees of code-paths");
    }

    @Test
    void everyClearOfTheJournalClearsTheEvidenceAndClearRecordingNamesIt() {
        try (RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 100, 1_000_000, 2, 10, 10, JournalSource.all()),
                RunIdentity.start(),
                false)) {
            AgentEvidence evidence = new AgentEvidence(null, null);
            FakeStore paths = new FakeStore("code-paths", BootUiPanels.CODE_PATHS);
            evidence.register(paths);
            journal.addListener(evidence);
            RuntimeJournalService service = new RuntimeJournalService(journal, null, evidence);

            assertThat(service.clear(new RuntimeJournalClearRequest(null)).status())
                    .isEqualTo(400);
            assertThat(paths.clears).as("an unconfirmed clear clears nothing").isEmpty();

            RuntimeJournalService.Response cleared = service.clear(new RuntimeJournalClearRequest(true));
            assertThat(cleared.status()).isEqualTo(200);
            assertThat(cleared.body().message())
                    .isEqualTo("Cleared 0 recorded events and the aggregates of this run, and the BootUI agent's"
                            + " evidence: 3 trees of code-paths.");
            assertThat(paths.clears).hasSize(1);

            journal.offloadRetainedData();
            assertThat(paths.clears).as("Free BootUI memory clears it too").hasSize(2);

            assertThat(service.status().agentEvidence().stores())
                    .singleElement()
                    .satisfies(store -> assertThat(store.store()).isEqualTo("code-paths"));
            assertThat(new RuntimeJournalService(journal, null).status().agentEvidence())
                    .isNull();
        }
    }
}
