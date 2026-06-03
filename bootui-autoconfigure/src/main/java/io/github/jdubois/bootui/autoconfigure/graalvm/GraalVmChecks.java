package io.github.jdubois.bootui.autoconfigure.graalvm;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.MethodCallTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchRule;
import io.github.jdubois.bootui.core.dto.GraalVmFindingDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Base class for readiness checks backed by a single ArchUnit {@link ArchRule}.
 *
 * <p>Subclasses build the rule for the current context; any failure to build or evaluate it is
 * captured and reported as an {@code ERROR} outcome so one broken check never aborts the scan.</p>
 */
abstract class AbstractArchUnitGraalVmCheck implements GraalVmCheck {

    private final GraalVmCheckDefinition definition;

    AbstractArchUnitGraalVmCheck(GraalVmCheckDefinition definition) {
        this.definition = definition;
    }

    @Override
    public final GraalVmCheckDefinition definition() {
        return definition;
    }

    abstract ArchRule rule(GraalVmContext context);

    @Override
    public GraalVmFindingDto evaluate(GraalVmContext context) {
        try {
            ArchRule rule = rule(context);
            if (rule == null) {
                return GraalVmCheckSupport.skipped(definition, "Check is not applicable to the imported classes.");
            }
            return GraalVmCheckSupport.evaluate(definition, rule, context);
            // Catch LinkageError as well as RuntimeException so one check that trips over an unresolvable class
            // reports an ERROR result instead of aborting the whole scan; VirtualMachineError still propagates.
        } catch (RuntimeException | LinkageError ex) {
            return GraalVmCheckSupport.error(definition, "Check could not be evaluated: " + ex.getMessage());
        }
    }
}

/**
 * Flags reflective API usage ({@code Class.forName}, {@code Method.invoke}, {@code Field} access,
 * {@code Class.getDeclared*}, {@code Constructor.newInstance}), which GraalVM cannot discover
 * statically and which therefore needs reflection metadata.
 */
final class ReflectionUsageCheck extends AbstractArchUnitGraalVmCheck {

    private static final Set<String> CLASS_LOOKUPS = Set.of(
            "forName",
            "newInstance",
            "getDeclaredMethod",
            "getDeclaredMethods",
            "getMethod",
            "getMethods",
            "getDeclaredField",
            "getDeclaredFields",
            "getField",
            "getFields",
            "getDeclaredConstructor",
            "getDeclaredConstructors",
            "getConstructor",
            "getConstructors");

    ReflectionUsageCheck() {
        super(
                new GraalVmCheckDefinition(
                        "GRAAL-REFLECT-001",
                        "Reflective API usage may need reflection metadata",
                        GraalVmCategory.REFLECTION,
                        "MEDIUM",
                        "Detects calls to the reflection API (Class.forName, Method.invoke, Field access, Class.getDeclared*, Constructor.newInstance) that GraalVM cannot resolve at build time.",
                        "Register the reflectively accessed types in reachability-metadata.json, or replace reflection with direct calls. Spring AOT already covers Spring-managed beans."));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("a reflection API method is called") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        String owner = target.getOwner().getName();
                        String name = target.getName();
                        if ("java.lang.Class".equals(owner)) {
                            return CLASS_LOOKUPS.contains(name);
                        }
                        if ("java.lang.reflect.Method".equals(owner)) {
                            return "invoke".equals(name);
                        }
                        if ("java.lang.reflect.Constructor".equals(owner)) {
                            return "newInstance".equals(name);
                        }
                        if ("java.lang.reflect.Field".equals(owner)) {
                            return name.startsWith("get") || name.startsWith("set");
                        }
                        return false;
                    }
                })
                .as("Classes should not use the reflection API without reachability metadata");
    }
}

/**
 * Flags dynamic JDK proxy creation ({@code Proxy.newProxyInstance}), which requires the proxied
 * interface set to be declared for native images.
 */
final class DynamicProxyCheck extends AbstractArchUnitGraalVmCheck {

    DynamicProxyCheck() {
        super(
                new GraalVmCheckDefinition(
                        "GRAAL-PROXY-001",
                        "Dynamic JDK proxies may need proxy metadata",
                        GraalVmCategory.PROXIES,
                        "MEDIUM",
                        "Detects calls to Proxy.newProxyInstance, which create JDK dynamic proxies whose interface lists must be known to native-image.",
                        "Declare the proxied interfaces in reachability-metadata.json, or prefer Spring's proxy mechanisms which are covered by Spring AOT."));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("Proxy.newProxyInstance() is called") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        return "newProxyInstance".equals(target.getName())
                                && "java.lang.reflect.Proxy"
                                        .equals(target.getOwner().getName());
                    }
                })
                .as("Classes should not create dynamic proxies without proxy metadata");
    }
}

/**
 * Flags dynamic resource loading ({@code getResource} / {@code getResourceAsStream}). Resources
 * loaded at runtime must be registered so they are embedded in the native image.
 */
final class ResourceAccessCheck extends AbstractArchUnitGraalVmCheck {

    ResourceAccessCheck() {
        super(
                new GraalVmCheckDefinition(
                        "GRAAL-RES-001",
                        "Runtime resource loading may need resource metadata",
                        GraalVmCategory.RESOURCES,
                        "LOW",
                        "Detects calls to Class/ClassLoader getResource and getResourceAsStream, whose resources must be embedded in the native image to be available at runtime.",
                        "Register the loaded resource paths (as globs) in reachability-metadata.json so native-image bundles them."));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("a resource is loaded by name") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        String name = target.getName();
                        if (!"getResource".equals(name) && !"getResourceAsStream".equals(name)) {
                            return false;
                        }
                        JavaClass owner = target.getOwner();
                        return owner.isAssignableTo(Class.class) || owner.isAssignableTo(ClassLoader.class);
                    }
                })
                .as("Classes should not load resources by name without resource metadata");
    }
}

/**
 * Flags application classes that implement {@link java.io.Serializable}. Serialized types need
 * serialization metadata in a native image.
 */
final class SerializationCheck implements GraalVmCheck {

    private static final GraalVmCheckDefinition DEFINITION = new GraalVmCheckDefinition(
            "GRAAL-SER-001",
            "Serializable types may need serialization metadata",
            GraalVmCategory.SERIALIZATION,
            "INFO",
            "Detects application classes that implement java.io.Serializable; types that are actually serialized at runtime require serialization metadata.",
            "If these types are serialized (e.g. via the JDK serialization protocol), register them under serialization in reachability-metadata.json.");

    @Override
    public GraalVmCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public GraalVmFindingDto evaluate(GraalVmContext context) {
        try {
            List<String> samples = new ArrayList<>();
            int count = 0;
            for (JavaClass javaClass : GraalVmClassPredicates.serializableTypes(context.classes())) {
                count++;
                if (samples.size() < GraalVmCheckSupport.maxSampleOccurrences()) {
                    samples.add(GraalVmCheckSupport.detail(javaClass.getName() + " implements java.io.Serializable"));
                }
            }
            if (count == 0) {
                return GraalVmCheckSupport.ok(DEFINITION);
            }
            return GraalVmCheckSupport.review(DEFINITION, count, samples);
        } catch (RuntimeException | LinkageError ex) {
            return GraalVmCheckSupport.error(DEFINITION, "Check could not be evaluated: " + ex.getMessage());
        }
    }
}

/**
 * Flags native access: calls to {@code System.loadLibrary} / {@code Runtime.loadLibrary} /
 * {@code Runtime.load} and dependencies on {@code sun.misc.Unsafe} / {@code jdk.internal.misc.Unsafe}.
 * Native code and {@code Unsafe} usage frequently need extra native-image configuration.
 */
final class NativeAccessCheck extends AbstractArchUnitGraalVmCheck {

    NativeAccessCheck() {
        super(
                new GraalVmCheckDefinition(
                        "GRAAL-NATIVE-001",
                        "Native access (JNI / Unsafe) may need native-image configuration",
                        GraalVmCategory.NATIVE_ACCESS,
                        "LOW",
                        "Detects loading of native libraries (System.loadLibrary, Runtime.load) and use of sun.misc.Unsafe / jdk.internal.misc.Unsafe, which often require JNI or extra native-image configuration.",
                        "Confirm the native libraries are available to the native image and add JNI configuration if needed; prefer supported APIs over Unsafe."));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("a native library is loaded") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        String owner = target.getOwner().getName();
                        String name = target.getName();
                        boolean loadLibrary = "loadLibrary".equals(name) || "load".equals(name);
                        return loadLibrary && ("java.lang.System".equals(owner) || "java.lang.Runtime".equals(owner));
                    }
                })
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("sun.misc.Unsafe")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("jdk.internal.misc.Unsafe")
                .as("Classes should not use native access without native-image configuration");
    }
}
