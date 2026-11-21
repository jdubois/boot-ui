package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DurableJournalIdentityTests {

    @Test
    void explicitAndLegacyIdsMigrateInPriorityOrder() {
        assertThat(DurableJournalIds.resolveNamespaceId(
                        " explicit-namespace ", "legacy-instance", "Alice", "workstation"))
                .isEqualTo("explicit-namespace");
        assertThat(DurableJournalIds.resolveNamespaceId("  ", " legacy-instance ", "Alice", "workstation"))
                .isEqualTo("legacy-instance");
    }

    @Test
    void applicationsOnOneMachineShareAnOpaqueNamespaceButNotApplicationIdentity() {
        DurableJournalIdentity orders =
                DurableJournalIdentity.create(null, null, null, "dev:orders", "Alice", "workstation");
        DurableJournalIdentity billing =
                DurableJournalIdentity.create(null, null, null, "dev:billing", "Alice", "workstation");

        assertThat(orders.namespaceId()).isEqualTo(billing.namespaceId()).matches("local-[0-9a-f]{64}");
        assertThat(orders.applicationId()).isEqualTo("dev:orders");
        assertThat(billing.applicationId()).isEqualTo("dev:billing");
    }

    @Test
    void differentUsersOrMachinesProduceDifferentNamespacesWithoutExposingInputs() {
        String first = DurableJournalIds.localNamespaceId("Alice", "Workstation-One");
        String otherUser = DurableJournalIds.localNamespaceId("Bob", "Workstation-One");
        String otherHost = DurableJournalIds.localNamespaceId("Alice", "Workstation-Two");

        assertThat(first).isNotEqualTo(otherUser).isNotEqualTo(otherHost);
        assertThat(first).doesNotContain("alice", "workstation");
        assertThat(DurableJournalIds.localNamespaceId(" Alice ", "workstation-one"))
                .isEqualTo(first);
    }

    @Test
    void applicationOverrideWinsAndBlankValuesUseSafeDefaults() {
        assertThat(DurableJournalIds.resolveApplicationId(" override ", "dev:orders"))
                .isEqualTo("override");
        assertThat(DurableJournalIds.resolveApplicationId("  ", "dev:orders")).isEqualTo("dev:orders");
        assertThat(DurableJournalIds.resolveApplicationId(null, "  ")).isEqualTo("application");

        assertThat(DurableJournalIds.localNamespaceId(" ", " "))
                .isEqualTo(DurableJournalIds.localNamespaceId(null, null));
    }

    @Test
    void runIdsAreUniqueUuidSizedValuesAndProcessIdentityIsAvailable() {
        Set<String> runIds = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String id = DurableJournalIds.newRunId();
            assertThat(UUID.fromString(id).toString()).isEqualTo(id);
            runIds.add(id);
        }
        assertThat(runIds).hasSize(100);
        assertThat(DurableJournalIds.processId())
                .isEqualTo(Long.toString(ProcessHandle.current().pid()));
        assertThat(DurableJournalIds.instanceId()).isNotBlank();
    }

    @Test
    void rejectsInvalidDurableIdentityAndSettings() {
        assertThatThrownBy(() -> new DurableJournalIdentity(" ", "orders", DurableJournalIds.newRunId(), "1", "i"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace");
        assertThatThrownBy(() -> DurableJournalIds.resolveNamespaceId("__bootui_internal__", null, "Alice", "host"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
        assertThatThrownBy(() -> new DurableJournalSettings(
                        "local-valid",
                        "orders",
                        DurableJournalIds.newRunId(),
                        "1",
                        "instance",
                        false,
                        Duration.ZERO,
                        1,
                        1,
                        1,
                        "bootui_journal",
                        Duration.ofDays(1),
                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flushInterval");
        assertThatThrownBy(() -> settings("not valid;"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tablePrefix");
    }

    private static DurableJournalSettings settings(String tablePrefix) {
        return new DurableJournalSettings(
                "local-valid",
                "orders",
                DurableJournalIds.newRunId(),
                "1",
                "instance",
                false,
                Duration.ofSeconds(5),
                500,
                1_000,
                500,
                tablePrefix,
                Duration.ofDays(7),
                false);
    }
}
