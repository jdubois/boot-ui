package io.github.jdubois.bootui.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.runtime.LaunchMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** MySQL needs no optional-import gate: the existing runtime indexing gate is its production boundary. */
class BootUiQuarkusProcessorMySqlTest {

    @ParameterizedTest
    @EnumSource(LaunchMode.class)
    void resourceIndexAndEngineProducerAreNeverRegisteredInProduction(LaunchMode mode) {
        List<IndexDependencyBuildItem> indexes = new ArrayList<>();
        List<AdditionalBeanBuildItem> beans = new ArrayList<>();
        new BootUiQuarkusProcessor()
                .registerConsole(
                        new LaunchModeBuildItem(mode, Optional.empty(), false, Optional.empty(), false),
                        indexes::add,
                        beans::add);
        if (mode == LaunchMode.NORMAL) {
            assertThat(indexes).isEmpty();
            assertThat(beans).isEmpty();
        } else {
            assertThat(indexes).hasSize(1);
            assertThat(beans)
                    .singleElement()
                    .satisfies(item -> assertThat(item.getBeanClasses())
                            .contains(
                                    "io.github.jdubois.bootui.quarkus.BootUiEngineProducer",
                                    "io.github.jdubois.bootui.quarkus.mcp.QuarkusMcpTools"));
        }
    }
}
