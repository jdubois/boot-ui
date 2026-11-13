package io.github.jdubois.bootui.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

class BootUiQuarkusProcessorHibernateTest {
    @Test
    void onlyResolvedPanacheExtensionProvesPlatformTransformation() {
        for (String artifact : List.of("quarkus-hibernate-orm-panache", "quarkus-hibernate-orm-panache-kotlin")) {
            assertThat(BootUiQuarkusProcessor.hasPanacheExtension(List.of(dependency(artifact, true))))
                    .isTrue();
            assertThat(BootUiQuarkusProcessor.hasPanacheExtension(List.of(dependency(artifact, false))))
                    .isFalse();
        }
        assertThat(BootUiQuarkusProcessor.hasPanacheExtension(List.of(dependency("quarkus-hibernate-orm", true))))
                .isFalse();
        assertThat(BootUiQuarkusProcessor.hasPanacheExtension(List.of())).isFalse();
    }

    @Test
    void runtimeDependencyInventoryExcludesWorkspaceModules() {
        ResolvedDependency workspaceModule = ResolvedDependencyBuilder.newInstance()
                .setGroupId("com.example")
                .setArtifactId("local-module")
                .setVersion("1.0.0-SNAPSHOT")
                .setWorkspaceModule()
                .build();
        ResolvedDependency thirdParty = dependency("quarkus-core", false);
        java.util.Set<String> coordinates = new java.util.LinkedHashSet<>();

        int skipped =
                BootUiQuarkusProcessor.collectRuntimeDependencies(List.of(workspaceModule, thirdParty), coordinates);

        assertThat(skipped).isZero();
        assertThat(coordinates).containsExactly("io.quarkus:quarkus-core:3.33.3.1");
    }

    private static ResolvedDependency dependency(String artifact, boolean extension) {
        ResolvedDependencyBuilder builder = ResolvedDependencyBuilder.newInstance()
                .setGroupId("io.quarkus")
                .setArtifactId(artifact)
                .setVersion("3.33.3.1");
        if (extension) builder.setRuntimeExtensionArtifact();
        return builder.build();
    }
}
