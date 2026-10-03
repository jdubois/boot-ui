package io.github.jdubois.bootui.engine.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentPackagesTests {

    @Test
    void packagesReduceToTheLongestPrefixTheirGroupShares() {
        assertThat(AgentPackages.reduce(List.of(
                        "com.example.app.web",
                        "com.example.app.data",
                        "com.example.app",
                        "org.acme.orders",
                        "io.github.jdubois.bootui.sample.web",
                        "io.github.jdubois.bootui.sample.data")))
                .containsExactlyInAnyOrder("com.example.app", "org.acme.orders", "io.github.jdubois.bootui.sample");
    }

    @Test
    void aPrefixKeepsTwoSegmentsSoUnrelatedLibrariesNeverMergeIntoATopLevelDomain() {
        assertThat(AgentPackages.reduce(List.of("com.example.one", "com.other.two", "app", "com.example")))
                .containsExactlyInAnyOrder("com.example", "com.other.two", "app");
    }

    @Test
    void bootUisOwnModulesAreLeftOutButItsSamplesAreNot() {
        assertThat(AgentPackages.reduce(List.of(
                        "io.github.jdubois.bootui.engine.javaagent",
                        "io.github.jdubois.bootui.core.dto",
                        "io.github.jdubois.bootui.spi",
                        "io.github.jdubois.bootui.quarkus.web",
                        "io.github.jdubois.bootui.agent.bridge",
                        "io.github.jdubois.bootui.sample")))
                .containsExactly("io.github.jdubois.bootui.sample");
        assertThat(AgentPackages.reduce(null)).isEmpty();
    }

    @Test
    void theDefaultPackageHasNoName() {
        assertThat(AgentPackages.packageOf("com.example.App")).isEqualTo("com.example");
        assertThat(AgentPackages.packageOf("App")).isNull();
        assertThat(AgentPackages.packageOf(null)).isNull();
    }
}
