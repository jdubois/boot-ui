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

    private static ResolvedDependency dependency(String artifact, boolean extension) {
        ResolvedDependencyBuilder builder = ResolvedDependencyBuilder.newInstance()
                .setGroupId("io.quarkus")
                .setArtifactId(artifact)
                .setVersion("3.33.3.1");
        if (extension) builder.setRuntimeExtensionArtifact();
        return builder.build();
    }
}
