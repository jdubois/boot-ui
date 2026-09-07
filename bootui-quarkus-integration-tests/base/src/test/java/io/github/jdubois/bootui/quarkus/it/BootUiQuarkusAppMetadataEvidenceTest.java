package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.example.bootui.it.advisor.ResolvedApplicationBeans;
import io.github.jdubois.bootui.quarkus.quarkusapp.QuarkusAppMetadataStore;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BootUiQuarkusAppMetadataEvidenceTest {
    @Test
    void resolvedArcTransformationsReachCapturedEvidenceWithoutBeanInvocation() {
        var metadata = QuarkusAppMetadataStore.load();
        assertThat(metadata.available()).isTrue();
        assertThat(metadata.problems()).noneMatch(problem -> problem.ruleId().startsWith("QA-CDI-"));
        assertThat(metadata.sharedFields())
                .filteredOn(field -> field.className().startsWith(ResolvedApplicationBeans.class.getName() + "$"))
                .extracting("className", "fieldName", "scope", "resource")
                .containsExactlyInAnyOrder(
                        tuple(ResolvedApplicationBeans.Parent.class.getName(), "inherited", "APPLICATION", false),
                        tuple(ResolvedApplicationBeans.Child.class.getName(), "inherited", "APPLICATION", false),
                        tuple(ResolvedApplicationBeans.Child.class.getName(), "own", "APPLICATION", false),
                        tuple(ResolvedApplicationBeans.Stereotyped.class.getName(), "exposed", "APPLICATION", false),
                        tuple(ResolvedApplicationBeans.Stereotyped.class.getName(), "mutable", "APPLICATION", false),
                        tuple(ResolvedApplicationBeans.DefaultResource.class.getName(), "shared", "SINGLETON", true),
                        tuple(ResolvedApplicationBeans.ContextResource.class.getName(), "shared", "SINGLETON", true));
    }
}
