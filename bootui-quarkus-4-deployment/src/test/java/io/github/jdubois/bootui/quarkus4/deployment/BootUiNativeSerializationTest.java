package io.github.jdubois.bootui.quarkus4.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DependencyScanStatusDto;
import io.github.jdubois.bootui.engine.mcp.McpScanSummaries;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.runtime.LaunchMode;
import java.io.IOException;
import java.util.ArrayList;
import org.jboss.jandex.Index;
import org.junit.jupiter.api.Test;

class BootUiNativeSerializationTest {

    @Test
    void compactFindingRowsKeepRecordAccessorsWithoutReflectingUnrelatedEngineTypes() throws IOException {
        var index = Index.of(DependencyScanStatusDto.class, McpScanSummaries.Finding.class, Unrelated.class);
        var reflection = new ArrayList<ReflectiveClassBuildItem>();
        var resources = new ArrayList<NativeImageResourceBuildItem>();
        new BootUiQuarkusProcessor()
                .registerNativeSerialization(
                        new BootUiConsoleBuildItem(LaunchMode.NORMAL, true, true),
                        new CombinedIndexBuildItem(index, index),
                        reflection::add,
                        resources::add);

        assertThat(reflection)
                .flatExtracting(ReflectiveClassBuildItem::getClassNames)
                .containsExactly(DependencyScanStatusDto.class.getName(), McpScanSummaries.Finding.class.getName());
        assertThat(reflection).allSatisfy(value -> {
            assertThat(value.isConstructors()).isTrue();
            assertThat(value.isMethods()).isTrue();
            assertThat(value.isFields()).isTrue();
        });
    }

    @Test
    void excludedNativeAndJvmNeverAddConsoleReflection() throws IOException {
        var index = Index.of(DependencyScanStatusDto.class, McpScanSummaries.Finding.class);
        for (var console : new BootUiConsoleBuildItem[] {
            new BootUiConsoleBuildItem(LaunchMode.NORMAL, true, false),
            new BootUiConsoleBuildItem(LaunchMode.NORMAL, false, true),
            new BootUiConsoleBuildItem(LaunchMode.DEVELOPMENT, false, false)
        }) {
            var reflection = new ArrayList<ReflectiveClassBuildItem>();
            var resources = new ArrayList<NativeImageResourceBuildItem>();
            new BootUiQuarkusProcessor()
                    .registerNativeSerialization(
                            console, new CombinedIndexBuildItem(index, index), reflection::add, resources::add);
            assertThat(reflection).isEmpty();
            assertThat(resources).isEmpty();
        }
    }

    record Unrelated(String value) {}
}
