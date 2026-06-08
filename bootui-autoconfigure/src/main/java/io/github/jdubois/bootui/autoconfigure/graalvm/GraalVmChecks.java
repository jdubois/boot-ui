package io.github.jdubois.bootui.autoconfigure.graalvm;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitCallTarget;
import com.tngtech.archunit.core.domain.AccessTarget.MethodCallTarget;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaStaticInitializer;
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
        super(new GraalVmCheckDefinition(
                "GRAAL-REFLECT-001",
                "Reflective API usage may need reflection metadata",
                GraalVmCategory.REFLECTION,
                "MEDIUM",
                "Detects calls to the reflection API (Class.forName, Method.invoke, Field access, Class.getDeclared*, Constructor.newInstance) that GraalVM cannot resolve at build time.",
                "Register the reflectively accessed types in reachability-metadata.json, or replace reflection with direct calls. Spring AOT already covers Spring-managed beans.",
                "https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/Reflection/"));
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
        super(new GraalVmCheckDefinition(
                "GRAAL-PROXY-001",
                "Dynamic JDK proxies may need proxy metadata",
                GraalVmCategory.PROXIES,
                "MEDIUM",
                "Detects calls to Proxy.newProxyInstance, which create JDK dynamic proxies whose interface lists must be known to native-image.",
                "Declare the proxied interfaces in reachability-metadata.json, or prefer Spring's proxy mechanisms which are covered by Spring AOT.",
                "https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/DynamicProxy/"));
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
        super(new GraalVmCheckDefinition(
                "GRAAL-RES-001",
                "Runtime resource loading may need resource metadata",
                GraalVmCategory.RESOURCES,
                "LOW",
                "Detects calls to Class/ClassLoader getResource and getResourceAsStream, whose resources must be embedded in the native image to be available at runtime.",
                "Register the loaded resource paths (as globs) in reachability-metadata.json so native-image bundles them.",
                "https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/Resources/"));
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
            "If these types are serialized (e.g. via the JDK serialization protocol), register them under serialization in reachability-metadata.json.",
            "https://www.graalvm.org/latest/reference-manual/native-image/metadata/");

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
        super(new GraalVmCheckDefinition(
                "GRAAL-NATIVE-001",
                "Native access (JNI / Unsafe) may need native-image configuration",
                GraalVmCategory.NATIVE_ACCESS,
                "LOW",
                "Detects loading of native libraries (System.loadLibrary, Runtime.load) and use of sun.misc.Unsafe / jdk.internal.misc.Unsafe, which often require JNI or extra native-image configuration.",
                "Confirm the native libraries are available to the native image and add JNI configuration if needed; prefer supported APIs over Unsafe.",
                "https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/JNI/"));
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

/**
 * Flags dynamic class loading through {@code ClassLoader.loadClass}, which resolves types by name at
 * run time and therefore cannot be discovered by native-image at build time.
 */
final class ClassLoaderUsageCheck extends AbstractArchUnitGraalVmCheck {

    ClassLoaderUsageCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-REFLECT-002",
                "Dynamic class loading may need reflection metadata",
                GraalVmCategory.REFLECTION,
                "MEDIUM",
                "Detects calls to ClassLoader.loadClass, which load classes by name at run time; native-image cannot discover such types statically.",
                "Register the dynamically loaded types under reflection in reachability-metadata.json, or replace ClassLoader.loadClass with direct class literals where possible.",
                "https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/Reflection/"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("ClassLoader.loadClass() is called") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        return "loadClass".equals(target.getName())
                                && target.getOwner().isAssignableTo(ClassLoader.class);
                    }
                })
                .as("Classes should not load classes by name without reflection metadata");
    }
}

/**
 * Flags deep reflection that bypasses access checks: {@code AccessibleObject.setAccessible} /
 * {@code trySetAccessible} and {@code MethodHandles.privateLookupIn}. Native-image must be told about
 * the affected members so they stay reachable and (where written) writable.
 */
final class DeepReflectionCheck extends AbstractArchUnitGraalVmCheck {

    private static final Set<String> ACCESSIBLE_METHODS = Set.of("setAccessible", "trySetAccessible");

    DeepReflectionCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-REFLECT-003",
                "Deep reflection (setAccessible / private lookups) may need reflection metadata",
                GraalVmCategory.REFLECTION,
                "MEDIUM",
                "Detects deep reflection that bypasses access checks: AccessibleObject.setAccessible/trySetAccessible and MethodHandles.privateLookupIn, which native-image must be told about to keep the members reachable.",
                "Register the accessed members (with allowWrite where needed) under reflection in reachability-metadata.json and ensure the required module opens are configured; prefer public APIs over deep reflection.",
                "https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/Reflection/"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("a deep-reflection method is called") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        String owner = target.getOwner().getName();
                        String name = target.getName();
                        if ("java.lang.invoke.MethodHandles".equals(owner)) {
                            return "privateLookupIn".equals(name);
                        }
                        return ACCESSIBLE_METHODS.contains(name)
                                && target.getOwner().isAssignableTo("java.lang.reflect.AccessibleObject");
                    }
                })
                .as("Classes should not use deep reflection without reflection metadata");
    }
}

/**
 * Flags reflective annotation queries on reflected members ({@code Method} / {@code Field} /
 * {@code Constructor} / {@code Parameter}). Native-image only retains those annotations when the
 * element is registered for reflection. Reads on {@code java.lang.Class} are intentionally ignored as
 * too common to be actionable.
 */
final class AnnotationReflectionCheck extends AbstractArchUnitGraalVmCheck {

    private static final Set<String> ANNOTATION_LOOKUPS = Set.of(
            "getAnnotation",
            "getAnnotations",
            "getDeclaredAnnotation",
            "getDeclaredAnnotations",
            "getAnnotationsByType",
            "getDeclaredAnnotationsByType",
            "isAnnotationPresent");

    AnnotationReflectionCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-REFLECT-004",
                "Reflective annotation access may need reflection metadata",
                GraalVmCategory.REFLECTION,
                "LOW",
                "Detects reflective annotation queries on reflected members (Method, Field, Constructor, Parameter), whose annotations native-image only retains when the element is registered for reflection.",
                "Register the inspected members under reflection in reachability-metadata.json so their annotations are available at run time.",
                "https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/Reflection/"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("annotations are read reflectively") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        if (!ANNOTATION_LOOKUPS.contains(target.getName())) {
                            return false;
                        }
                        JavaClass owner = target.getOwner();
                        if ("java.lang.Class".equals(owner.getName())) {
                            return false;
                        }
                        return owner.isAssignableTo("java.lang.reflect.AnnotatedElement");
                    }
                })
                .as("Classes should not read annotations from reflected members without reflection metadata");
    }
}

/**
 * Flags {@code ResourceBundle.getBundle}, whose localized {@code .properties} files must be embedded
 * in the native image (with all locale variants) to be available at run time.
 */
final class ResourceBundleCheck extends AbstractArchUnitGraalVmCheck {

    ResourceBundleCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-RES-002",
                "Resource bundle loading may need resource-bundle metadata",
                GraalVmCategory.RESOURCES,
                "LOW",
                "Detects calls to ResourceBundle.getBundle, whose localized .properties files must be registered so native-image embeds them.",
                "Register the bundle base names under bundles in reachability-metadata.json so native-image includes every locale variant.",
                "https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/Resources/"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("ResourceBundle.getBundle() is called") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        return "getBundle".equals(target.getName())
                                && "java.util.ResourceBundle"
                                        .equals(target.getOwner().getName());
                    }
                })
                .as("Classes should not load resource bundles without resource-bundle metadata");
    }
}

/**
 * Flags {@code ServiceLoader.load} / {@code loadInstalled}, which discover providers through
 * {@code META-INF/services} and reflectively instantiate them — both must be reachable in a native
 * image.
 */
final class ServiceLoaderCheck extends AbstractArchUnitGraalVmCheck {

    private static final Set<String> SERVICE_LOADS = Set.of("load", "loadInstalled");

    ServiceLoaderCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-SERVICE-001",
                "Service loading may need service metadata",
                GraalVmCategory.SERVICE_LOADER,
                "LOW",
                "Detects calls to ServiceLoader.load, which discover providers via META-INF/services; native-image must reach the provider configuration and reflectively instantiate the implementations.",
                "Ensure the META-INF/services provider files are on the classpath and register the provider implementations under reflection in reachability-metadata.json.",
                "https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/Reflection/"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("ServiceLoader.load() is called") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        return SERVICE_LOADS.contains(target.getName())
                                && "java.util.ServiceLoader"
                                        .equals(target.getOwner().getName());
                    }
                })
                .as("Classes should not load services without service metadata");
    }
}

/**
 * Flags static initializers that perform file I/O or start threads/processes directly. With
 * build-time class initialization these side effects run during the native build, capturing
 * build-time state or failing the build. Detection is limited to side effects emitted directly in the
 * static initializer (helper methods and lambdas are out of scope).
 */
final class BuildTimeInitializationCheck extends AbstractArchUnitGraalVmCheck {

    private static final Set<String> FILE_IO_TYPES = Set.of(
            "java.io.FileInputStream",
            "java.io.FileOutputStream",
            "java.io.FileReader",
            "java.io.FileWriter",
            "java.io.RandomAccessFile");

    BuildTimeInitializationCheck() {
        super(
                new GraalVmCheckDefinition(
                        "GRAAL-INIT-001",
                        "Static initializer I/O or thread starts may break build-time initialization",
                        GraalVmCategory.BUILD_TIME_INIT,
                        "LOW",
                        "Detects static initializers that perform file I/O or start threads/processes directly; with build-time class initialization these run during the native build, capturing build-time state or failing the build.",
                        "Move the side effect out of the static initializer, or initialize the class at run time (e.g. --initialize-at-run-time=<class>) so the I/O or thread starts when the application runs.",
                        "https://www.graalvm.org/latest/reference-manual/native-image/optimizations-and-performance/ClassInitialization/"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callCodeUnitWhere(
                        new DescribedPredicate<JavaCall<?>>("a static initializer performs I/O or starts a thread") {
                            @Override
                            public boolean test(JavaCall<?> call) {
                                if (!(call.getOrigin() instanceof JavaStaticInitializer)) {
                                    return false;
                                }
                                CodeUnitCallTarget target = call.getTarget();
                                JavaClass owner = target.getOwner();
                                String ownerName = owner.getName();
                                String name = target.getName();
                                if ("start".equals(name) && owner.isAssignableTo(Thread.class)) {
                                    return true;
                                }
                                if ("exec".equals(name) && "java.lang.Runtime".equals(ownerName)) {
                                    return true;
                                }
                                if ("start".equals(name) && "java.lang.ProcessBuilder".equals(ownerName)) {
                                    return true;
                                }
                                if ("java.nio.file.Files".equals(ownerName)) {
                                    return true;
                                }
                                return "<init>".equals(name) && FILE_IO_TYPES.contains(ownerName);
                            }
                        })
                .as("Static initializers should not perform I/O or start threads for build-time initialization");
    }
}

/**
 * Flags application classes that declare {@code native} methods. The JNI entry points and the backing
 * native library must be configured for the native image.
 */
final class NativeMethodCheck implements GraalVmCheck {

    private static final GraalVmCheckDefinition DEFINITION = new GraalVmCheckDefinition(
            "GRAAL-NATIVE-002",
            "Native method declarations may need JNI configuration",
            GraalVmCategory.NATIVE_ACCESS,
            "LOW",
            "Detects application classes that declare native methods; the JNI entry points and their backing native library must be configured for the native image.",
            "Provide JNI configuration under jni in reachability-metadata.json and ensure the native library is bundled with and loadable by the native image.",
            "https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/JNI/");

    @Override
    public GraalVmCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public GraalVmFindingDto evaluate(GraalVmContext context) {
        try {
            List<String> samples = new ArrayList<>();
            int count = 0;
            for (JavaClass javaClass : context.classes()) {
                for (JavaMethod method : javaClass.getMethods()) {
                    if (method.getModifiers().contains(JavaModifier.NATIVE)) {
                        count++;
                        if (samples.size() < GraalVmCheckSupport.maxSampleOccurrences()) {
                            samples.add(GraalVmCheckSupport.detail(
                                    javaClass.getName() + " declares native method " + method.getName() + "()"));
                        }
                    }
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
