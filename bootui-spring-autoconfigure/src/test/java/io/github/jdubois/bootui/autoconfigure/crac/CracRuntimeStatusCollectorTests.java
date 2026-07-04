package io.github.jdubois.bootui.autoconfigure.crac;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.CracRuntimeStatusDto;
import io.github.jdubois.bootui.engine.crac.CracRuntimeInventory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CracRuntimeStatusCollectorTests {

    @Test
    void reportsMissingApiWhenOrgCracIsAbsent() {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(), List::of, className -> false, CracRuntimeInventory::empty, () -> null);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.cracApiPresent()).isFalse();
        assertThat(status.cracCapableJvm()).isFalse();
        assertThat(status.checkpointOnRefresh()).isFalse();
        assertThat(status.checkpointTo()).isNull();
        assertThat(status.summary()).contains("not on the classpath");
    }

    @Test
    void detectsNoOpShimWhenOnlyApiIsPresent() {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(), List::of, "org.crac.Core"::equals, CracRuntimeInventory::empty, () -> null);

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
                present::contains,
                CracRuntimeInventory::empty,
                () -> "onRefresh");

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
                new MockEnvironment(),
                () -> List.of("-XX:CRaCRestoreFrom=/snapshots/app"),
                className -> true,
                CracRuntimeInventory::empty,
                () -> null);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.restoreFrom()).isEqualTo("/snapshots/app");
        assertThat(status.cracJvmArgs()).containsExactly("-XX:CRaCRestoreFrom=/snapshots/app");
    }

    @Test
    void addsFrozenConfigurationCaveatWhenCheckpointOnRefresh() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.context.checkpoint", "onRefresh");
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                environment, List::of, className -> false, CracRuntimeInventory::empty, () -> "onRefresh");

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.checkpointOnRefresh()).isTrue();
        assertThat(status.restoreCaveats()).anyMatch(caveat -> caveat.contains("frozen into the checkpoint"));
    }

    @Test
    void hasNoFrozenConfigurationCaveatWithoutCheckpointOnRefresh() {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(), List::of, className -> false, CracRuntimeInventory::empty, () -> null);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.restoreCaveats()).noneMatch(caveat -> caveat.contains("frozen into the checkpoint"));
    }

    @Test
    void surfacesConnectionPoolsAsRestoreCaveat() {
        CracRuntimeInventory inventory =
                new CracRuntimeInventory(List.of("dataSource : com.zaxxer.hikari.HikariDataSource"));
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(), List::of, className -> false, () -> inventory, () -> null);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.restoreCaveats())
                .anyMatch(caveat -> caveat.contains("connection pool") && caveat.contains("CRAC-POOL-001"));
    }

    @Test
    void warnsWhenEnvironmentClaimsCheckpointOnRefreshButSpringPropertyIsUnset() {
        // Spring Boot's Environment can see spring.context.checkpoint from application.yml or an OS
        // environment variable, but Spring Framework's DefaultLifecycleProcessor only ever honors the
        // property through org.springframework.core.SpringProperties (a JVM system property or a
        // classpath spring.properties file). This models the mismatch: the Environment claims onRefresh
        // is set, but the actual SpringProperties-backed source has no value, so no automatic checkpoint
        // will really be taken.
        MockEnvironment environment = new MockEnvironment().withProperty("spring.context.checkpoint", "onRefresh");
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                environment, List::of, className -> false, CracRuntimeInventory::empty, () -> null);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.checkpointOnRefresh()).isFalse();
        assertThat(status.restoreCaveats())
                .anyMatch(caveat -> caveat.contains("SpringProperties")
                        && caveat.contains("No automatic checkpoint will actually be taken"));
    }

    @Test
    void hasNoMismatchCaveatWhenSpringPropertyAgreesWithEnvironment() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.context.checkpoint", "onRefresh");
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                environment, List::of, className -> false, CracRuntimeInventory::empty, () -> "onRefresh");

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.restoreCaveats()).noneMatch(caveat -> caveat.contains("SpringProperties"));
    }

    @Test
    void hasNoMismatchCaveatWhenNeitherSourceClaimsCheckpointOnRefresh() {
        CracRuntimeStatusCollector collector = new CracRuntimeStatusCollector(
                new MockEnvironment(), List::of, className -> false, CracRuntimeInventory::empty, () -> null);

        CracRuntimeStatusDto status = collector.collect();

        assertThat(status.restoreCaveats()).noneMatch(caveat -> caveat.contains("SpringProperties"));
    }
}
