package io.github.jdubois.bootui.engine.graalvm;

import java.util.List;

/**
 * Fixed, reviewable registry of the curated native-image readiness checks. Adding a check means
 * adding one focused class plus an entry here; the panel never derives checks from project input.
 */
final class GraalVmCheckRegistry {

    private static final List<GraalVmCheck> ACTIVE_CHECKS = List.of(
            new ReflectionUsageCheck(),
            new ClassLoaderUsageCheck(),
            new DeepReflectionCheck(),
            new AnnotationReflectionCheck(),
            new DynamicProxyCheck(),
            new ResourceAccessCheck(),
            new ResourceBundleCheck(),
            new ServiceLoaderCheck(),
            new SerializationCheck(),
            new ActiveSerializationCheck(),
            new BuildTimeInitializationCheck(),
            new BuildTimeStateCaptureCheck(),
            new NativeAccessCheck(),
            new NativeMethodCheck(),
            new RuntimeClassGenerationCheck(),
            new RuntimeClasspathScanningCheck(),
            new RuntimeSingletonRegistrationCheck(),
            new RuntimeInstanceSupplierCheck(),
            new SpringAotConditionedBeansCheck(),
            new RuntimeApplicationContextCheck(),
            new SpelUsageCheck(),
            new MethodHandleUsageCheck(),
            new SecurityProviderCheck(),
            new JmxUsageCheck(),
            new ForeignFunctionUsageCheck());

    private GraalVmCheckRegistry() {}

    static List<GraalVmCheck> activeChecks() {
        return ACTIVE_CHECKS;
    }
}
