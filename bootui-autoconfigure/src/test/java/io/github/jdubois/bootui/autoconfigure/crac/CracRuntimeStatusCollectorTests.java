package io.github.jdubois.bootui.autoconfigure.crac;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.CracRuntimeStatusDto;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CracRuntimeStatusCollectorTests {

    @Test
    void reportsMissingApiWhenOrgCracIsAbsent() {
        CracRuntimeStatusCollector collector =
                new CracRuntimeStatusCollector(new MockEnvironment(), List::of, className -> false);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.cracApiPresent()).isFalse();
        assertThat(status.cracCapableJvm()).isFalse();
        assertThat(status.checkpointOnRefresh()).isFalse();
        assertThat(status.checkpointTo()).isNull();
        assertThat(status.summary()).contains("not on the classpath");
    }

    @Test
    void detectsNoOpShimWhenOnlyApiIsPresent() {
        CracRuntimeStatusCollector collector =
                new CracRuntimeStatusCollector(new MockEnvironment(), List::of, "org.crac.Core"::equals);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.cracApiPresent()).isTrue();
        assertThat(status.cracCapableJvm()).isFalse();
        assertThat(status.summary()).contains("no CRaC implementation");
    }

    @Test
    void detectsCracCapableJvmAndCheckpointArguments() {
        Set<String> present = Set.of("org.crac.Core", "jdk.crac.Core");
        MockEnvironment environment = new MockEnvironment().withProperty("spring.context.checkpoint", "onRefresh");
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                environment,
                () -> List.of("-XX:CRaCCheckpointTo=/tmp/cr", "-Dfoo=bar", "-XX:+UseG1GC"),
                present::contains);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.cracApiPresent()).isTrue();
        assertThat(status.cracCapableJvm()).isTrue();
        assertThat(status.checkpointOnRefresh()).isTrue();
        assertThat(status.checkpointTo()).isEqualTo("/tmp/cr");
        assertThat(status.cracJvmArgs()).containsExactly("-XX:CRaCCheckpointTo=/tmp/cr");
        assertThat(status.summary()).contains("onRefresh");
    }

    @Test
    void parsesRestoreFromArgument() {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(), () -> List.of("-XX:CRaCRestoreFrom=/snapshots/app"), className -> true);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.restoreFrom()).isEqualTo("/snapshots/app");
        assertThat(status.cracJvmArgs()).containsExactly("-XX:CRaCRestoreFrom=/snapshots/app");
    }

    @Test
    void addsFrozenConfigurationCaveatWhenCheckpointOnRefresh() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.context.checkpoint", "onRefresh");
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(environment, CracRuntimeInventory::empty);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.restoreCaveats()).anyMatch(caveat -> caveat.contains("frozen into the checkpoint"));
    }

    @Test
    void hasNoFrozenConfigurationCaveatWithoutCheckpointOnRefresh() {
        CracRuntimeStatusCollector collector =
                new CracRuntimeStatusCollector(new MockEnvironment(), CracRuntimeInventory::empty);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.restoreCaveats()).noneMatch(caveat -> caveat.contains("frozen into the checkpoint"));
    }

    @Test
    void surfacesConnectionPoolsAsRestoreCaveat() {
        CracRuntimeInventory inventory =
                new CracRuntimeInventory(List.of("dataSource : com.zaxxer.hikari.HikariDataSource"), null);
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(new MockEnvironment(), () -> inventory);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.restoreCaveats())
                .anyMatch(caveat -> caveat.contains("connection pool") && caveat.contains("CRAC-POOL-001"));
    }
}
