package io.github.jdubois.bootui.autoconfigure.graalvm;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.ActiveSerializer;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.AnnotationReader;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.CglibProxyGenerator;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.ClassGraphScanner;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.ClasspathScanner;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.CleanComponent;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.DeepReflector;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.DevOnlyConfiguration;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.DynamicClassLoader;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.FieldMetadataReader;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.FieldValueAccessor;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.FilesMetadataInitializer;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.InstanceSupplierRegistrar;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.JmxUser;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.MessagesLoader;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.MethodHandleUser;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.NativeMethodHolder;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.ProxyClassFactory;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.ReflectionsScanner;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.RuntimeClassGenerator;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.RuntimeSingletonRegistrar;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.SecondaryContextCreator;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.SecurityProviderRegistrar;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.ServiceConsumer;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.SpelUser;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.StateCapturingInitializer;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.StaticInitializerComponent;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.SupplierBeanDefiner;
import io.github.jdubois.bootui.autoconfigure.graalvm.fixtures.UnrelatedSupplierHolder;
import io.github.jdubois.bootui.core.dto.GraalVmFindingDto;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Positive and negative coverage for each curated readiness check in isolation. */
class GraalVmChecksTests {

    @Test
    void everyActiveCheckExposesAnHttpsLearnMoreUrl() {
        assertThat(GraalVmCheckRegistry.activeChecks())
                .allSatisfy(check -> assertThat(check.definition().learnMoreUrl())
                        .as("learnMoreUrl for %s", check.definition().id())
                        .isNotBlank()
                        .startsWith("https://"));
    }

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

    @Test
    void reflectionUsageCheckDetectsFieldValueAccessButNotMetadataAccess() {
        GraalVmFindingDto finding = evaluate(new ReflectionUsageCheck(), FieldValueAccessor.class);
        assertThat(finding.id()).isEqualTo("GRAAL-REFLECT-001");
        assertThat(finding.status()).isEqualTo("REVIEW");
        // Field metadata accessors (getName/getType/getModifiers) no longer count as reflection usage.
        assertThat(evaluate(new ReflectionUsageCheck(), FieldMetadataReader.class)
                        .status())
                .isEqualTo("OK");
    }

    @Test
    void dynamicProxyCheckDetectsGetProxyClass() {
        GraalVmFindingDto finding = evaluate(new DynamicProxyCheck(), ProxyClassFactory.class);
        assertThat(finding.id()).isEqualTo("GRAAL-PROXY-001");
        assertThat(finding.status()).isEqualTo("REVIEW");
        assertThat(evaluate(new DynamicProxyCheck(), CleanComponent.class).status())
                .isEqualTo("OK");
    }

    @Test
    void buildTimeInitializationCheckIgnoresFilesMetadataCalls() {
        // Files.exists is a metadata helper, not filesystem-touching I/O, so it must not be flagged.
        assertThat(evaluate(new BuildTimeInitializationCheck(), FilesMetadataInitializer.class)
                        .status())
                .isEqualTo("OK");
    }

    @Test
    void runtimeClassGenerationCheckDetectsDefineClass() {
        GraalVmFindingDto finding = evaluate(new RuntimeClassGenerationCheck(), RuntimeClassGenerator.class);
        assertThat(finding.id()).isEqualTo("GRAAL-CLASSGEN-001");
        assertThat(finding.severity()).isEqualTo("HIGH");
        assertThat(finding.status()).isEqualTo("REVIEW");
        assertThat(evaluate(new RuntimeClassGenerationCheck(), CglibProxyGenerator.class)
                        .status())
                .isEqualTo("REVIEW");
        assertThat(evaluate(new RuntimeClassGenerationCheck(), CleanComponent.class)
                        .status())
                .isEqualTo("OK");
    }

    @Test
    void buildTimeStateCaptureCheckDetectsEnvironmentAndSeedCapture() {
        GraalVmFindingDto finding = evaluate(new BuildTimeStateCaptureCheck(), StateCapturingInitializer.class);
        assertThat(finding.id()).isEqualTo("GRAAL-INIT-002");
        assertThat(finding.status()).isEqualTo("REVIEW");
        assertThat(finding.occurrenceCount()).isPositive();
        assertThat(evaluate(new BuildTimeStateCaptureCheck(), CleanComponent.class)
                        .status())
                .isEqualTo("OK");
    }

    @Test
    void activeSerializationCheckDetectsWriteObject() {
        GraalVmFindingDto finding = evaluate(new ActiveSerializationCheck(), ActiveSerializer.class);
        assertThat(finding.id()).isEqualTo("GRAAL-SER-002");
        assertThat(finding.status()).isEqualTo("REVIEW");
        assertThat(evaluate(new ActiveSerializationCheck(), CleanComponent.class)
                        .status())
                .isEqualTo("OK");
    }

    @Test
    void runtimeClasspathScanningCheckDetectsComponentScanning() {
        GraalVmFindingDto finding = evaluate(new RuntimeClasspathScanningCheck(), ClasspathScanner.class);
        assertThat(finding.id()).isEqualTo("GRAAL-SCAN-001");
        assertThat(finding.severity()).isEqualTo("HIGH");
        assertThat(finding.status()).isEqualTo("REVIEW");
        assertThat(evaluate(new RuntimeClasspathScanningCheck(), CleanComponent.class)
                        .status())
                .isEqualTo("OK");
    }

    @Test
    void runtimeClasspathScanningCheckDetectsLibraryConstructorScans() {
        // The Reflections library commonly scans in its constructor; ClassGraph scans through its API.
        assertThat(evaluate(new RuntimeClasspathScanningCheck(), ReflectionsScanner.class)
                        .status())
                .isEqualTo("REVIEW");
        assertThat(evaluate(new RuntimeClasspathScanningCheck(), ClassGraphScanner.class)
                        .status())
                .isEqualTo("REVIEW");
    }

    @Test
    void runtimeSingletonRegistrationCheckDetectsRegisterSingleton() {
        GraalVmFindingDto finding = evaluate(new RuntimeSingletonRegistrationCheck(), RuntimeSingletonRegistrar.class);
        assertThat(finding.id()).isEqualTo("SPRING-AOT-001");
        assertThat(finding.severity()).isEqualTo("MEDIUM");
        assertThat(finding.status()).isEqualTo("REVIEW");
        assertThat(evaluate(new RuntimeSingletonRegistrationCheck(), CleanComponent.class)
                        .status())
                .isEqualTo("OK");
    }

    @Test
    void runtimeInstanceSupplierCheckDetectsSetInstanceSupplier() {
        GraalVmFindingDto finding = evaluate(new RuntimeInstanceSupplierCheck(), InstanceSupplierRegistrar.class);
        assertThat(finding.id()).isEqualTo("SPRING-AOT-002");
        assertThat(finding.severity()).isEqualTo("HIGH");
        assertThat(finding.status()).isEqualTo("REVIEW");
        // BeanDefinitionBuilder.genericBeanDefinition(Class, Supplier) overload is also captured.
        assertThat(evaluate(new RuntimeInstanceSupplierCheck(), SupplierBeanDefiner.class)
                        .status())
                .isEqualTo("REVIEW");
        assertThat(evaluate(new RuntimeInstanceSupplierCheck(), CleanComponent.class)
                        .status())
                .isEqualTo("OK");
        // A non-Spring class with its own setInstanceSupplier method must not be flagged.
        assertThat(evaluate(new RuntimeInstanceSupplierCheck(), UnrelatedSupplierHolder.class)
                        .status())
                .isEqualTo("OK");
    }

    @Test
    void springAotConditionedBeansCheckDetectsProfileOnConfiguration() {
        GraalVmFindingDto finding = evaluate(new SpringAotConditionedBeansCheck(), DevOnlyConfiguration.class);
        assertThat(finding.id()).isEqualTo("SPRING-AOT-003");
        assertThat(finding.severity()).isEqualTo("MEDIUM");
        assertThat(finding.status()).isEqualTo("REVIEW");
        assertThat(finding.occurrenceCount()).isPositive();
        assertThat(evaluate(new SpringAotConditionedBeansCheck(), CleanComponent.class)
                        .status())
                .isEqualTo("OK");
    }

    @Test
    void runtimeApplicationContextCheckDetectsSecondaryContextCreation() {
        GraalVmFindingDto finding = evaluate(new RuntimeApplicationContextCheck(), SecondaryContextCreator.class);
        assertThat(finding.id()).isEqualTo("SPRING-AOT-004");
        assertThat(finding.severity()).isEqualTo("HIGH");
        assertThat(finding.status()).isEqualTo("REVIEW");
        assertThat(evaluate(new RuntimeApplicationContextCheck(), CleanComponent.class)
                        .status())
                .isEqualTo("OK");
    }

    @Test
    void spelUsageCheckDetectsRuntimeParseExpression() {
        GraalVmFindingDto finding = evaluate(new SpelUsageCheck(), SpelUser.class);
        assertThat(finding.id()).isEqualTo("GRAAL-SPEL-001");
        assertThat(finding.severity()).isEqualTo("MEDIUM");
        assertThat(finding.status()).isEqualTo("REVIEW");
        assertThat(evaluate(new SpelUsageCheck(), CleanComponent.class).status())
                .isEqualTo("OK");
    }

    @Test
    void methodHandleUsageCheckDetectsFindVirtual() {
        GraalVmFindingDto finding = evaluate(new MethodHandleUsageCheck(), MethodHandleUser.class);
        assertThat(finding.id()).isEqualTo("GRAAL-MH-001");
        assertThat(finding.severity()).isEqualTo("MEDIUM");
        assertThat(finding.status()).isEqualTo("REVIEW");
        assertThat(evaluate(new MethodHandleUsageCheck(), CleanComponent.class).status())
                .isEqualTo("OK");
    }

    @Test
    void securityProviderCheckDetectsAddProvider() {
        GraalVmFindingDto finding = evaluate(new SecurityProviderCheck(), SecurityProviderRegistrar.class);
        assertThat(finding.id()).isEqualTo("GRAAL-SEC-001");
        assertThat(finding.severity()).isEqualTo("MEDIUM");
        assertThat(finding.status()).isEqualTo("REVIEW");
        assertThat(evaluate(new SecurityProviderCheck(), CleanComponent.class).status())
                .isEqualTo("OK");
    }

    @Test
    void jmxUsageCheckDetectsGetPlatformMBeanServer() {
        GraalVmFindingDto finding = evaluate(new JmxUsageCheck(), JmxUser.class);
        assertThat(finding.id()).isEqualTo("GRAAL-JMX-001");
        assertThat(finding.severity()).isEqualTo("LOW");
        assertThat(finding.status()).isEqualTo("REVIEW");
        assertThat(evaluate(new JmxUsageCheck(), CleanComponent.class).status()).isEqualTo("OK");
    }

    @Test
    void foreignFunctionCheckMatchesLinkerByName() {
        assertThat(ForeignFunctionUsageCheck.isForeignLinkerClass("java.lang.foreign.Linker"))
                .isTrue();
        assertThat(ForeignFunctionUsageCheck.isForeignLinkerClass("java.lang.foreign.Linker$Option"))
                .isTrue();
        assertThat(ForeignFunctionUsageCheck.isForeignLinkerClass("java.lang.foreign.MemorySegment"))
                .isFalse();
        assertThat(ForeignFunctionUsageCheck.isForeignLinkerClass("java.lang.String"))
                .isFalse();
    }

    @Test
    void foreignFunctionCheckExposesMetadataAndPassesCleanClasses() {
        GraalVmFindingDto finding = evaluate(new ForeignFunctionUsageCheck(), CleanComponent.class);
        assertThat(finding.id()).isEqualTo("GRAAL-FFM-001");
        assertThat(finding.severity()).isEqualTo("LOW");
        assertThat(finding.category()).isEqualTo(GraalVmCategory.NATIVE_ACCESS.label());
        assertThat(finding.status()).isEqualTo("OK");
    }
}
