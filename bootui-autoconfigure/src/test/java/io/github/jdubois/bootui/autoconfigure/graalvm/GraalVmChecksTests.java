package io.github.jdubois.bootui.autoconfigure.graalvm;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.AnnotationReader;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.CleanComponent;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.DeepReflector;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.DynamicClassLoader;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.MessagesLoader;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.NativeMethodHolder;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.ServiceConsumer;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.StaticInitializerComponent;
import io.github.jdubois.bootui.core.dto.GraalVmFindingDto;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Positive and negative coverage for each curated readiness check in isolation. */
class GraalVmChecksTests {

    private GraalVmFindingDto evaluate(GraalVmCheck check, Class<?>... classes) {
        JavaClasses imported = new ClassFileImporter().importClasses(classes);
        return check.evaluate(new GraalVmContext(imported, List.of("io.github.jdubois.bootui")));
    }

    @Test
    void classLoaderUsageCheckDetectsDynamicClassLoading() {
        GraalVmFindingDto finding = evaluate(new ClassLoaderUsageCheck(), DynamicClassLoader.class);
        assertThat(finding.id()).isEqualTo("GRAAL-REFLECT-002");
        assertThat(finding.status()).isEqualTo("REVIEW");
        assertThat(finding.occurrenceCount()).isPositive();
        assertThat(evaluate(new ClassLoaderUsageCheck(), CleanComponent.class).status())
                .isEqualTo("OK");
    }

    @Test
    void deepReflectionCheckDetectsSetAccessible() {
        GraalVmFindingDto finding = evaluate(new DeepReflectionCheck(), DeepReflector.class);
        assertThat(finding.id()).isEqualTo("GRAAL-REFLECT-003");
        assertThat(finding.status()).isEqualTo("REVIEW");
        assertThat(evaluate(new DeepReflectionCheck(), CleanComponent.class).status())
                .isEqualTo("OK");
    }

    @Test
    void annotationReflectionCheckDetectsMemberAnnotationReads() {
        GraalVmFindingDto finding = evaluate(new AnnotationReflectionCheck(), AnnotationReader.class);
        assertThat(finding.id()).isEqualTo("GRAAL-REFLECT-004");
        assertThat(finding.status()).isEqualTo("REVIEW");
        assertThat(evaluate(new AnnotationReflectionCheck(), CleanComponent.class)
                        .status())
                .isEqualTo("OK");
    }

    @Test
    void resourceBundleCheckDetectsGetBundle() {
        GraalVmFindingDto finding = evaluate(new ResourceBundleCheck(), MessagesLoader.class);
        assertThat(finding.id()).isEqualTo("GRAAL-RES-002");
        assertThat(finding.status()).isEqualTo("REVIEW");
        assertThat(evaluate(new ResourceBundleCheck(), CleanComponent.class).status())
                .isEqualTo("OK");
    }

    @Test
    void serviceLoaderCheckDetectsServiceLoad() {
        GraalVmFindingDto finding = evaluate(new ServiceLoaderCheck(), ServiceConsumer.class);
        assertThat(finding.id()).isEqualTo("GRAAL-SERVICE-001");
        assertThat(finding.status()).isEqualTo("REVIEW");
        assertThat(evaluate(new ServiceLoaderCheck(), CleanComponent.class).status())
                .isEqualTo("OK");
    }

    @Test
    void buildTimeInitializationCheckDetectsStaticInitializerSideEffects() {
        GraalVmFindingDto finding = evaluate(new BuildTimeInitializationCheck(), StaticInitializerComponent.class);
        assertThat(finding.id()).isEqualTo("GRAAL-INIT-001");
        assertThat(finding.status()).isEqualTo("REVIEW");
        assertThat(evaluate(new BuildTimeInitializationCheck(), CleanComponent.class)
                        .status())
                .isEqualTo("OK");
    }

    @Test
    void nativeMethodCheckDetectsNativeDeclarations() {
        GraalVmFindingDto finding = evaluate(new NativeMethodCheck(), NativeMethodHolder.class);
        assertThat(finding.id()).isEqualTo("GRAAL-NATIVE-002");
        assertThat(finding.status()).isEqualTo("REVIEW");
        assertThat(finding.occurrenceCount()).isEqualTo(1);
        assertThat(evaluate(new NativeMethodCheck(), CleanComponent.class).status())
                .isEqualTo("OK");
    }
}
