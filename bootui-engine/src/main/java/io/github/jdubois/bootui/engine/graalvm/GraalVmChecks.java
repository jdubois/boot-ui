package io.github.jdubois.bootui.engine.graalvm;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitCallTarget;
import com.tngtech.archunit.core.domain.AccessTarget.MethodCallTarget;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
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

    // Reflective field value accessors only. The metadata accessors (getName, getType, getModifiers,
    // getDeclaringClass, getAnnotation, ...) do not read or write the field's value and so do not by
    // themselves require reflection metadata, so matching every get*/set* produced false positives.
    private static final Set<String> FIELD_VALUE_ACCESSORS = Set.of(
            "get",
            "set",
            "getBoolean",
            "getByte",
            "getChar",
            "getShort",
            "getInt",
            "getLong",
            "getFloat",
            "getDouble",
            "setBoolean",
            "setByte",
            "setChar",
            "setShort",
            "setInt",
            "setLong",
            "setFloat",
            "setDouble");

    ReflectionUsageCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-REFLECT-001",
                "Reflective API usage may need reflection metadata",
                GraalVmCategory.REFLECTION,
                "MEDIUM",
                "Detects calls to the reflection API (Class.forName, Method.invoke, Field value get/set, Class.getDeclared*, Constructor.newInstance) that GraalVM cannot resolve at build time. Reflective metadata accessors such as Field.getName() are intentionally ignored.",
                "Register the reflectively accessed types in reachability-metadata.json, or for application code register them with Spring's RuntimeHints (e.g. via @ImportRuntimeHints / RuntimeHintsRegistrar). Spring AOT already covers Spring-managed beans.",
                "https://www.graalvm.org/latest/reference-manual/native-image/metadata/"));
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
                            return FIELD_VALUE_ACCESSORS.contains(name);
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
                "Detects calls to Proxy.newProxyInstance and Proxy.getProxyClass, which create JDK dynamic proxies whose interface lists must be known to native-image. When the interface array is a compile-time constant, native-image may auto-register the proxy; runtime-computed interface sets always need explicit metadata.",
                "Declare the proxied interfaces in reachability-metadata.json, or for application code register them with Spring's RuntimeHints (RuntimeHints.proxies().registerJdkProxy(...) via @ImportRuntimeHints). Spring's own proxy mechanisms are covered by Spring AOT.",
                "https://www.graalvm.org/latest/reference-manual/native-image/metadata/"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("a JDK proxy class is created") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        String name = target.getName();
                        return ("newProxyInstance".equals(name) || "getProxyClass".equals(name))
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
                "Detects calls to Class/ClassLoader getResource and getResourceAsStream, whose resources must be embedded in the native image to be available at runtime. Calls with a constant resource name are often already detected automatically by native-image; runtime-computed names always need registration.",
                "Register the loaded resource paths (as globs) in reachability-metadata.json, or for application code register them with Spring's RuntimeHints (RuntimeHints.resources() via @ImportRuntimeHints) so native-image bundles them.",
                "https://www.graalvm.org/latest/reference-manual/native-image/metadata/"));
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
            "Detects application classes that implement java.io.Serializable (non-enum, concrete types); types that are actually serialized at runtime require serialization metadata. If GRAAL-SER-002 (active JDK serialization) also fires, the listed types are likely serialized at runtime. Enum types are excluded because GraalVM handles standard enum serialization automatically.",
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
 * {@code Runtime.load} and unsupported {@code Unsafe} class-definition operations. Ordinary Unsafe
 * memory access is supported by Native Image and must not be reported as a blanket JNI concern.
 */
final class NativeAccessCheck extends AbstractArchUnitGraalVmCheck {

    private static final Set<String> UNSUPPORTED_UNSAFE_OPERATIONS = Set.of("defineClass", "defineAnonymousClass");

    NativeAccessCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-NATIVE-001",
                "Native libraries or unsupported Unsafe operations need native-image review",
                GraalVmCategory.NATIVE_ACCESS,
                "LOW",
                "Detects loading of native libraries (System.loadLibrary, Runtime.load) and unsupported Unsafe class-definition operations. Ordinary Unsafe memory access is supported by Native Image and is intentionally not flagged.",
                "Confirm loaded native libraries are available to the native image and add JNI configuration where needed; replace Unsafe runtime class definition with build-time generation.",
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
                        if (loadLibrary && ("java.lang.System".equals(owner) || "java.lang.Runtime".equals(owner))) {
                            return true;
                        }
                        return ("sun.misc.Unsafe".equals(owner) || "jdk.internal.misc.Unsafe".equals(owner))
                                && UNSUPPORTED_UNSAFE_OPERATIONS.contains(name);
                    }
                })
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
                "https://www.graalvm.org/latest/reference-manual/native-image/metadata/"));
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
                "https://www.graalvm.org/latest/reference-manual/native-image/metadata/"));
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
                "https://www.graalvm.org/latest/reference-manual/native-image/metadata/"));
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
                        String ownerName = target.getOwner().getName();
                        return "java.lang.reflect.Method".equals(ownerName)
                                || "java.lang.reflect.Field".equals(ownerName)
                                || "java.lang.reflect.Constructor".equals(ownerName)
                                || "java.lang.reflect.Parameter".equals(ownerName);
                    }
                })
                .as("Classes should not read annotations from reflected members without reflection metadata");
    }
}

/**
 * Flags calls to {@code Unsafe.allocateInstance(Class)} on {@code sun.misc.Unsafe} or
 * {@code jdk.internal.misc.Unsafe}. Unsafe allocation constructs an instance without invoking any
 * constructor, bypassing the construction path that native-image's reachability analysis tracks, so
 * the allocated type needs its own {@code unsafeAllocated} reflection metadata in addition to normal
 * type registration.
 */
final class UnsafeAllocateInstanceCheck extends AbstractArchUnitGraalVmCheck {

    UnsafeAllocateInstanceCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-REFLECT-005",
                "Unsafe.allocateInstance bypasses construction and needs unsafeAllocated metadata",
                GraalVmCategory.REFLECTION,
                "MEDIUM",
                "Detects calls to Unsafe.allocateInstance(Class) on sun.misc.Unsafe or jdk.internal.misc.Unsafe. This constructs an instance without invoking any constructor, which bypasses the construction path native-image's reachability analysis tracks; without metadata this throws MissingReflectionRegistrationError at run time.",
                "Register the allocated type under reflection in reachability-metadata.json with \"unsafeAllocated\": true (in addition to its normal type registration), or replace Unsafe.allocateInstance with a public constructor or factory method where possible.",
                "https://www.graalvm.org/latest/reference-manual/native-image/metadata/"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("Unsafe.allocateInstance() is called") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        if (!"allocateInstance".equals(target.getName())) {
                            return false;
                        }
                        String ownerName = target.getOwner().getName();
                        return "sun.misc.Unsafe".equals(ownerName) || "jdk.internal.misc.Unsafe".equals(ownerName);
                    }
                })
                .as("Classes should not use Unsafe.allocateInstance without unsafeAllocated metadata");
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
                "https://www.graalvm.org/latest/reference-manual/native-image/metadata/"));
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
                "https://www.graalvm.org/latest/reference-manual/native-image/metadata/"));
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

    // java.nio.file.Files methods that only inspect lightweight path metadata. Everything else on
    // Files actually touches the filesystem (reads, traversals, attribute/owner reads, writes, ...),
    // which is a genuine build-time-initialization concern, so it is flagged. Matching every Files.*
    // call (the previous behaviour) over-reported these common predicate checks.
    private static final Set<String> FILES_METADATA_PREDICATES = Set.of(
            "exists",
            "notExists",
            "isDirectory",
            "isRegularFile",
            "isSymbolicLink",
            "isReadable",
            "isWritable",
            "isExecutable",
            "isHidden",
            "isSameFile",
            "getFileAttributeView");

    BuildTimeInitializationCheck() {
        super(
                new GraalVmCheckDefinition(
                        "GRAAL-INIT-001",
                        "Build-time-initialized classes must not perform static I/O or start threads",
                        GraalVmCategory.BUILD_TIME_INIT,
                        "LOW",
                        "Detects static initializers that perform file I/O (java.io file streams or filesystem-touching java.nio.file.Files calls) or start threads/processes directly. Since GraalVM 21.3+ classes are run-time-initialized by default, this only applies when the class is explicitly initialized at build time via the native-image --initialize-at-build-time flag. Spring AOT is one way to arrive at that configuration (it can compute and pass the flag for Spring-managed classes on the application's behalf), but the flag itself — not Spring AOT — is the actual mechanism native-image reads, so this also applies to build-time initialization configured directly or by other means.",
                        "If the class is listed under --initialize-at-build-time, move the side effect out of the static initializer or switch the class to --initialize-at-run-time so the I/O or thread starts when the application runs rather than during the native build.",
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
                                    return !FILES_METADATA_PREDICATES.contains(name);
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

/**
 * Flags runtime bytecode/class generation (e.g. {@code ClassLoader.defineClass},
 * {@code MethodHandles.Lookup.defineClass/defineHiddenClass}, CGLIB {@code Enhancer}, ByteBuddy,
 * Javassist). A closed-world native image has no compiler at run time, so these calls have no
 * general-purpose support. The native-image agent's experimental "Predefined Classes" mode is a
 * narrow, best-effort escape hatch (see {@code GraalVmCheckDefinition#learnMoreUrl}), not a general
 * fix: it replays only the exact bytecode traced ahead of time, so it cannot help when generation is
 * name/bytecode-varying (e.g. driven by counters or timestamps).
 */
final class RuntimeClassGenerationCheck extends AbstractArchUnitGraalVmCheck {

    RuntimeClassGenerationCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-CLASSGEN-001",
                "Runtime class generation has no general-purpose support in native images",
                GraalVmCategory.CLASS_GENERATION,
                "HIGH",
                "Detects runtime bytecode/class generation (ClassLoader.defineClass, MethodHandles.Lookup.defineClass/defineHiddenClass, CGLIB Enhancer, ByteBuddy, Javassist). A closed-world native image has no compiler at run time, so these calls are not supported for arbitrary, build-time-unknown bytecode. The native-image agent's experimental \"Predefined Classes\" mode (experimental-class-define-support) can trace and replay a bounded set of previously-seen classes, but it is best-effort, cannot handle bytecode/names that vary between runs, and is not a substitute for build-time class generation.",
                "Generate the classes at build time (e.g. via Spring AOT / build-time processing) instead of at run time, or replace the dynamically generated types with statically compiled equivalents. If runtime class generation truly cannot be avoided, evaluate the native-image agent's experimental Predefined Classes support as a narrow fallback — but note its known limitations (single load per class loader per execution, no build-time initialization, no support for varying bytecode/names) before relying on it.",
                "https://www.graalvm.org/latest/reference-manual/native-image/metadata/ExperimentalAgentOptions/"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("a class is generated or defined at run time") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        String name = target.getName();
                        JavaClass owner = target.getOwner();
                        String ownerName = owner.getName();
                        if ("defineClass".equals(name)) {
                            return "java.lang.invoke.MethodHandles$Lookup".equals(ownerName)
                                    || owner.isAssignableTo(ClassLoader.class);
                        }
                        if ("defineHiddenClass".equals(name) || "defineHiddenClassWithClassData".equals(name)) {
                            return "java.lang.invoke.MethodHandles$Lookup".equals(ownerName);
                        }
                        if (("create".equals(name) || "createClass".equals(name) || "generateClass".equals(name))
                                && ownerName.endsWith(".cglib.proxy.Enhancer")) {
                            return true;
                        }
                        if ("toClass".equals(name) && "javassist.CtClass".equals(ownerName)) {
                            return true;
                        }
                        return "load".equals(name) && ownerName.startsWith("net.bytebuddy.");
                    }
                })
                .as("Classes should not generate or define classes at run time");
    }
}

/** Flags attempts to obtain the JDK compiler, which is unavailable in a native executable. */
final class SystemJavaCompilerCheck extends AbstractArchUnitGraalVmCheck {

    SystemJavaCompilerCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-JDK-001",
                "The system Java compiler is unavailable in native images",
                GraalVmCategory.CLASS_GENERATION,
                "HIGH",
                "Detects ToolProvider.getSystemJavaCompiler(), which requests a runtime Java compiler. Native images contain ahead-of-time compiled application code and do not provide javac at run time.",
                "Compile or generate code during the application build and include the resulting classes in the native image; do not compile Java source inside the running application.",
                "https://www.graalvm.org/jdk25/reference-manual/native-image/metadata/Compatibility/"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(
                        new DescribedPredicate<JavaMethodCall>("ToolProvider.getSystemJavaCompiler() is called") {
                            @Override
                            public boolean test(JavaMethodCall call) {
                                MethodCallTarget target = call.getTarget();
                                return "javax.tools.ToolProvider"
                                                .equals(target.getOwner().getName())
                                        && "getSystemJavaCompiler".equals(target.getName());
                            }
                        })
                .as("Classes should not request a runtime Java compiler in a native image");
    }
}

/** Flags JSR-223 engine discovery, which depends on runtime service loading and dynamic execution. */
final class ScriptEngineUsageCheck extends AbstractArchUnitGraalVmCheck {

    ScriptEngineUsageCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-JDK-002",
                "JSR-223 script engines require native-image-specific support",
                GraalVmCategory.CLASS_GENERATION,
                "HIGH",
                "Detects construction of ScriptEngineManager. JSR-223 discovers engines with ServiceLoader and engines generally load or generate executable code dynamically, which a closed-world native image cannot assume is available.",
                "Remove runtime scripting, replace it with statically compiled application logic, or validate a specific engine's documented Native Image integration and register all of its service, resource, reflection, and native requirements.",
                "https://www.graalvm.org/jdk25/reference-manual/native-image/metadata/Compatibility/"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callConstructorWhere(
                        new DescribedPredicate<JavaConstructorCall>(
                                "a javax.script.ScriptEngineManager is constructed") {
                            @Override
                            public boolean test(JavaConstructorCall call) {
                                return "javax.script.ScriptEngineManager"
                                        .equals(call.getTarget().getOwner().getName());
                            }
                        })
                .as("Classes should not discover script engines without a validated native-image integration");
    }
}

/**
 * Flags static initializers that capture environment- or time-sensitive state ({@code System.getenv},
 * current time, {@code java.time} {@code now()}, default {@code Locale}/{@code TimeZone},
 * {@code InetAddress}, {@code Random}/{@code SecureRandom} seeds, {@code UUID.randomUUID}). With
 * build-time class initialization those values are frozen during the native build instead of being
 * read when the application runs.
 */
final class BuildTimeStateCaptureCheck extends AbstractArchUnitGraalVmCheck {

    private static final Set<String> SYSTEM_STATE =
            Set.of("getenv", "getProperty", "getProperties", "currentTimeMillis", "nanoTime");
    private static final Set<String> INET_LOOKUPS = Set.of("getLocalHost", "getByName", "getAllByName", "getByAddress");

    BuildTimeStateCaptureCheck() {
        super(
                new GraalVmCheckDefinition(
                        "GRAAL-INIT-002",
                        "Build-time-initialized classes must not capture build-machine state",
                        GraalVmCategory.BUILD_TIME_INIT,
                        "LOW",
                        "Detects static initializers that read environment- or time-sensitive state (System.getenv/getProperty, current time, java.time now(), default Locale/TimeZone, InetAddress, Random/SecureRandom seeds, UUID.randomUUID). Since GraalVM 21.3+ classes are run-time-initialized by default, this is only a concern when the class is explicitly initialized at build time via the native-image --initialize-at-build-time flag. Spring AOT is one way to arrive at that configuration (it can compute and pass the flag for Spring-managed classes on the application's behalf), but the flag itself — not Spring AOT — is the actual mechanism native-image reads, so this also applies to build-time initialization configured directly or by other means.",
                        "If the class is listed under --initialize-at-build-time, move the state capture into a runtime code path or switch the class to --initialize-at-run-time so the values are read when the native image starts rather than baked in during the build.",
                        "https://www.graalvm.org/latest/reference-manual/native-image/optimizations-and-performance/ClassInitialization/"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callCodeUnitWhere(
                        new DescribedPredicate<JavaCall<?>>("a static initializer captures build-machine state") {
                            @Override
                            public boolean test(JavaCall<?> call) {
                                if (!(call.getOrigin() instanceof JavaStaticInitializer)) {
                                    return false;
                                }
                                CodeUnitCallTarget target = call.getTarget();
                                JavaClass owner = target.getOwner();
                                String ownerName = owner.getName();
                                String name = target.getName();
                                if ("java.lang.System".equals(ownerName)) {
                                    return SYSTEM_STATE.contains(name);
                                }
                                if (ownerName.startsWith("java.time.") && "now".equals(name)) {
                                    return true;
                                }
                                if ("java.time.ZoneId".equals(ownerName) && "systemDefault".equals(name)) {
                                    return true;
                                }
                                if (("java.util.TimeZone".equals(ownerName) || "java.util.Locale".equals(ownerName))
                                        && "getDefault".equals(name)) {
                                    return true;
                                }
                                if ("java.net.InetAddress".equals(ownerName)) {
                                    return INET_LOOKUPS.contains(name);
                                }
                                if ("java.util.UUID".equals(ownerName) && "randomUUID".equals(name)) {
                                    return true;
                                }
                                if (owner.isAssignableTo("java.util.Random")) {
                                    return "<init>".equals(name) || name.startsWith("next");
                                }
                                return false;
                            }
                        })
                .as("Static initializers should not capture build-machine state for build-time initialization");
    }
}

/**
 * Flags active JDK serialization ({@code ObjectOutputStream.writeObject} /
 * {@code ObjectInputStream.readObject}) — types actually serialized at run time must be registered for
 * serialization in a native image.
 */
final class ActiveSerializationCheck extends AbstractArchUnitGraalVmCheck {

    ActiveSerializationCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-SER-002",
                "Active JDK serialization may need serialization metadata",
                GraalVmCategory.SERIALIZATION,
                "MEDIUM",
                "Detects calls to ObjectOutputStream.writeObject / ObjectInputStream.readObject, i.e. types serialized via the JDK serialization protocol at run time, which native-image must be told about explicitly.",
                "Register every serialized type under serialization in reachability-metadata.json (or with Spring's RuntimeHints serialization registration), or prefer a serialization format that does not need build-time registration.",
                "https://www.graalvm.org/latest/reference-manual/native-image/metadata/"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("a type is serialized via the JDK protocol") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        String name = target.getName();
                        JavaClass owner = target.getOwner();
                        if ("writeObject".equals(name) || "writeUnshared".equals(name)) {
                            return owner.isAssignableTo("java.io.ObjectOutputStream");
                        }
                        if ("readObject".equals(name) || "readUnshared".equals(name)) {
                            return owner.isAssignableTo("java.io.ObjectInputStream");
                        }
                        return false;
                    }
                })
                .as("Classes should not use JDK serialization without serialization metadata");
    }
}

/**
 * Flags runtime classpath/component scanning (Spring's
 * {@code ClassPathScanningCandidateComponentProvider}, the Reflections library, or ClassGraph). The
 * closed-world native image has no scannable classpath at run time, so such scans return nothing.
 */
final class RuntimeClasspathScanningCheck extends AbstractArchUnitGraalVmCheck {

    RuntimeClasspathScanningCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-SCAN-001",
                "Runtime classpath scanning does not work in native images",
                GraalVmCategory.CLASSPATH_SCANNING,
                "HIGH",
                "Detects runtime classpath/component scanning (ClassPathScanningCandidateComponentProvider.findCandidateComponents, the Reflections library, or ClassGraph); the closed-world native image has no scannable classpath at run time.",
                "Resolve the scanning at build time. For Spring components rely on Spring AOT/component indexing rather than runtime scanning; replace library-based scanning with an explicit, statically known set of types.",
                "https://docs.spring.io/spring-framework/reference/core/aot.html"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callCodeUnitWhere(new DescribedPredicate<JavaCall<?>>("the classpath is scanned at run time") {
                    @Override
                    public boolean test(JavaCall<?> call) {
                        CodeUnitCallTarget target = call.getTarget();
                        String name = target.getName();
                        String ownerName = target.getOwner().getName();
                        if ("findCandidateComponents".equals(name)
                                && "org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider"
                                        .equals(ownerName)) {
                            return true;
                        }
                        // The Reflections library and ClassGraph commonly scan in their constructors, so match any
                        // code unit (including <init>) on those types.
                        if ("org.reflections.Reflections".equals(ownerName)) {
                            return true;
                        }
                        return ownerName.startsWith("io.github.classgraph.");
                    }
                })
                .as("Classes should not scan the classpath at run time");
    }
}

/**
 * Flags runtime bean singleton registration ({@code SingletonBeanRegistry.registerSingleton}). Spring
 * AOT processes the bean factory at build time, so singletons added dynamically are invisible to the
 * AOT-generated context and to native-image.
 */
final class RuntimeSingletonRegistrationCheck extends AbstractArchUnitGraalVmCheck {

    RuntimeSingletonRegistrationCheck() {
        super(new GraalVmCheckDefinition(
                "SPRING-AOT-001",
                "Runtime bean singleton registration is not captured by Spring AOT",
                GraalVmCategory.SPRING_AOT,
                "MEDIUM",
                "Detects SingletonBeanRegistry.registerSingleton(...) calls that add beans to the context at run time; Spring AOT processes the bean factory at build time, so dynamically registered singletons are invisible to the AOT-generated context and native-image.",
                "Register the bean through standard build-time configuration (@Bean / @Component / a BeanFactoryInitializationAotContribution) so Spring AOT can see it. For programmatic AOT-aware registration consider Spring Framework 7's BeanRegistrar API. Note: the singleton instance itself is present at runtime; the risk is only for reflective construction and AOT-generated context completeness.",
                "https://docs.spring.io/spring-framework/reference/core/aot.html"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("a singleton is registered at run time") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        if (!"registerSingleton".equals(target.getName())) {
                            return false;
                        }
                        return target.getOwner()
                                .isAssignableTo("org.springframework.beans.factory.config.SingletonBeanRegistry");
                    }
                })
                .as("Classes should not register singletons at run time under Spring AOT");
    }
}

/**
 * Flags programmatic bean instance suppliers ({@code AbstractBeanDefinition.setInstanceSupplier} or
 * {@code registerBean}/{@code BeanDefinitionBuilder} with a {@link java.util.function.Supplier}).
 * Spring AOT cannot trace through the supplier lambda at build time, so the bean's type and
 * dependencies may be missing from the native image.
 */
final class RuntimeInstanceSupplierCheck extends AbstractArchUnitGraalVmCheck {

    private static final Set<String> SUPPLIER_BEAN_METHODS =
            Set.of("registerBean", "genericBeanDefinition", "rootBeanDefinition");

    RuntimeInstanceSupplierCheck() {
        super(new GraalVmCheckDefinition(
                "SPRING-AOT-002",
                "Programmatic instance suppliers are not captured by Spring AOT",
                GraalVmCategory.SPRING_AOT,
                "HIGH",
                "Detects bean definitions backed by a programmatic instance supplier (setInstanceSupplier, or registerBean/BeanDefinitionBuilder with a Supplier); Spring AOT cannot trace through the supplier lambda at build time, so the bean's type and dependencies may be missing from the native image.",
                "Prefer declarative bean definitions (@Bean methods / component scanning) whose types Spring AOT can resolve, or use Spring Framework 7's BeanRegistrar / BeanRegistrarDsl for AOT-friendly programmatic registration; alternatively provide a RuntimeHintsRegistrar that registers the supplied type for reflection.",
                "https://docs.spring.io/spring-framework/reference/core/aot.html"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(
                        new DescribedPredicate<JavaMethodCall>(
                                "a bean instance supplier is registered programmatically") {
                            @Override
                            public boolean test(JavaMethodCall call) {
                                if (call.getOriginOwner().getName().endsWith("__BeanDefinitions")) {
                                    return false;
                                }
                                MethodCallTarget target = call.getTarget();
                                String name = target.getName();
                                String ownerName = target.getOwner().getName();
                                if ("setInstanceSupplier".equals(name)) {
                                    return target.getOwner()
                                            .isAssignableTo(
                                                    "org.springframework.beans.factory.support.AbstractBeanDefinition");
                                }
                                if (ownerName.startsWith("org.springframework.")
                                        && SUPPLIER_BEAN_METHODS.contains(name)) {
                                    for (JavaClass parameterType : target.getRawParameterTypes()) {
                                        if ("java.util.function.Supplier".equals(parameterType.getName())) {
                                            return true;
                                        }
                                    }
                                }
                                return false;
                            }
                        })
                .as("Classes should not register programmatic instance suppliers under Spring AOT");
    }
}

/**
 * Flags environment-sensitive conditions on application configuration and bean methods. Spring AOT
 * evaluates these conditions at build time. Deliberate {@code @AutoConfiguration} classes are
 * excluded because condition-driven auto-configuration is the framework's intended AOT model.
 */
final class SpringAotConditionedBeansCheck implements GraalVmCheck {

    private static final GraalVmCheckDefinition DEFINITION = new GraalVmCheckDefinition(
            "SPRING-AOT-003",
            "Environment-sensitive bean conditions freeze selection at AOT build time",
            GraalVmCategory.SPRING_AOT,
            "MEDIUM",
            "Detects @Profile, @ConditionalOnProperty, custom @Conditional, or @ConditionalOnExpression on application configuration/components and @Bean methods. Spring AOT evaluates these conditions at build time; deliberate @AutoConfiguration classes are excluded.",
            "Ensure the profiles and properties active during the AOT build (native-image compilation) match the intended production configuration, or restructure the configuration to use explicit build-time selection rather than runtime conditions.",
            "https://docs.spring.io/spring-framework/reference/core/aot.html");

    private static final GraalVmCheckDefinition EXPRESSION_DEFINITION = new GraalVmCheckDefinition(
            "SPRING-AOT-003",
            "@ConditionalOnExpression is evaluated early during AOT processing",
            GraalVmCategory.SPRING_AOT,
            "HIGH",
            "@ConditionalOnExpression is evaluated during AOT processing and a bean reference in its SpEL expression can initialize that bean too early, before post-processing such as configuration-properties binding.",
            "Replace bean-referencing SpEL with property/class conditions that Spring AOT can evaluate without instantiating beans. If the expression is unavoidable, ensure it references no beans and uses build-time-stable inputs.",
            "https://docs.spring.io/spring-boot/api/java/org/springframework/boot/autoconfigure/condition/ConditionalOnExpression.html");

    private static final List<String> SPRING_COMPONENT_ANNOTATIONS = List.of(
            "org.springframework.context.annotation.Configuration",
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController");

    private static final List<String> CONDITION_ANNOTATIONS = List.of(
            "org.springframework.context.annotation.Profile",
            "org.springframework.context.annotation.Conditional",
            "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
            "org.springframework.boot.autoconfigure.condition.ConditionalOnExpression");

    private static final String BEAN_ANNOTATION = "org.springframework.context.annotation.Bean";
    private static final String AUTO_CONFIGURATION = "org.springframework.boot.autoconfigure.AutoConfiguration";
    private static final String CONDITIONAL_ON_EXPRESSION =
            "org.springframework.boot.autoconfigure.condition.ConditionalOnExpression";
    private static final String CONDITIONAL = "org.springframework.context.annotation.Conditional";

    @Override
    public GraalVmCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public GraalVmFindingDto evaluate(GraalVmContext context) {
        try {
            List<String> samples = new ArrayList<>();
            int count = 0;
            boolean expressionFound = false;
            for (JavaClass javaClass : context.classes()) {
                boolean autoConfiguration = javaClass.isAnnotatedWith(AUTO_CONFIGURATION);
                boolean classExpressionReferencesBean = expressionReferencesBean(javaClass);
                if (hasAnyAnnotation(javaClass, SPRING_COMPONENT_ANNOTATIONS)
                        && ((!autoConfiguration && hasRelevantCondition(javaClass)) || classExpressionReferencesBean)) {
                    count++;
                    expressionFound |= classExpressionReferencesBean;
                    if (samples.size() < GraalVmCheckSupport.maxSampleOccurrences()) {
                        samples.add(GraalVmCheckSupport.detail(
                                javaClass.getName() + " is a Spring component with an AOT-time condition"));
                    }
                }
                for (JavaMethod method : javaClass.getMethods()) {
                    if (method.isAnnotatedWith(BEAN_ANNOTATION)
                            && ((!autoConfiguration && hasRelevantCondition(method))
                                    || expressionReferencesBean(method))) {
                        count++;
                        expressionFound |= expressionReferencesBean(method);
                        if (samples.size() < GraalVmCheckSupport.maxSampleOccurrences()) {
                            samples.add(GraalVmCheckSupport.detail(javaClass.getName() + "." + method.getName()
                                    + " @Bean method has an AOT-time condition"));
                        }
                    }
                }
            }
            if (count == 0) {
                return GraalVmCheckSupport.ok(DEFINITION);
            }
            return GraalVmCheckSupport.review(expressionFound ? EXPRESSION_DEFINITION : DEFINITION, count, samples);
        } catch (RuntimeException | LinkageError ex) {
            return GraalVmCheckSupport.error(DEFINITION, "Check could not be evaluated: " + ex.getMessage());
        }
    }

    private static boolean hasAnyAnnotation(JavaClass javaClass, List<String> annotationNames) {
        for (String annotation : annotationNames) {
            if (javaClass.isAnnotatedWith(annotation) || javaClass.isMetaAnnotatedWith(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyMethodAnnotation(JavaMethod method, List<String> annotationNames) {
        for (String annotation : annotationNames) {
            if (method.isAnnotatedWith(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRelevantCondition(JavaClass javaClass) {
        return hasAnyAnnotation(javaClass, CONDITION_ANNOTATIONS) || hasCustomConditional(javaClass.getAnnotations());
    }

    private static boolean hasRelevantCondition(JavaMethod method) {
        return hasAnyMethodAnnotation(method, CONDITION_ANNOTATIONS) || hasCustomConditional(method.getAnnotations());
    }

    private static boolean hasCustomConditional(Iterable<? extends JavaAnnotation<?>> annotations) {
        for (JavaAnnotation<?> annotation : annotations) {
            String name = annotation.getRawType().getName();
            if (!name.startsWith("org.springframework.boot.autoconfigure.condition.")
                    && annotation.getRawType().isMetaAnnotatedWith(CONDITIONAL)) {
                return true;
            }
        }
        return false;
    }

    private static boolean expressionReferencesBean(JavaClass javaClass) {
        return javaClass
                .tryGetAnnotationOfType(CONDITIONAL_ON_EXPRESSION)
                .flatMap(annotation -> annotation.get("value"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(SpringAotConditionedBeansCheck::containsBeanReference)
                .orElse(false);
    }

    private static boolean expressionReferencesBean(JavaMethod method) {
        return method.tryGetAnnotationOfType(CONDITIONAL_ON_EXPRESSION)
                .flatMap(annotation -> annotation.get("value"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(SpringAotConditionedBeansCheck::containsBeanReference)
                .orElse(false);
    }

    private static boolean containsBeanReference(String expression) {
        for (int i = 0; i < expression.length() - 1; i++) {
            if (expression.charAt(i) == '@' && Character.isJavaIdentifierStart(expression.charAt(i + 1))) {
                return true;
            }
        }
        return false;
    }
}

/**
 * Flags runtime construction of {@code AnnotationConfigApplicationContext} or
 * {@code GenericApplicationContext}, and {@code SpringApplicationBuilder.child()} calls. Secondary
 * contexts created programmatically are never processed by Spring AOT, so their beans and runtime
 * hints are absent from the native image.
 */
final class RuntimeApplicationContextCheck extends AbstractArchUnitGraalVmCheck {

    RuntimeApplicationContextCheck() {
        super(new GraalVmCheckDefinition(
                "SPRING-AOT-004",
                "Runtime ApplicationContext creation outside main entry point is not AOT-processed",
                GraalVmCategory.SPRING_AOT,
                "HIGH",
                "Detects runtime construction of AnnotationConfigApplicationContext or GenericApplicationContext and SpringApplicationBuilder.child() calls; secondary contexts created programmatically are never processed by Spring AOT, so their beans and hints are absent from the native image.",
                "Consolidate configuration into the main application context processed by Spring AOT, or use @Import / @ImportResource to include additional configuration statically at build time.",
                "https://docs.spring.io/spring-framework/reference/core/aot.html"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callCodeUnitWhere(
                        new DescribedPredicate<JavaCall<?>>(
                                "a secondary ApplicationContext is created or a child context is built") {
                            @Override
                            public boolean test(JavaCall<?> call) {
                                CodeUnitCallTarget target = call.getTarget();
                                String name = target.getName();
                                String ownerName = target.getOwner().getName();
                                if ("<init>".equals(name)) {
                                    return "org.springframework.context.annotation.AnnotationConfigApplicationContext"
                                                    .equals(ownerName)
                                            || "org.springframework.context.support.GenericApplicationContext"
                                                    .equals(ownerName);
                                }
                                return "child".equals(name)
                                        && "org.springframework.boot.builder.SpringApplicationBuilder"
                                                .equals(ownerName);
                            }
                        })
                .as("Classes should not create secondary ApplicationContexts outside the AOT-processed main context");
    }
}

/**
 * Flags programmatic SpEL expression parsing ({@code ExpressionParser.parseExpression} /
 * {@code parseRaw}). Runtime-parsed expressions use reflection to access object properties that is
 * not visible to native-image, and the SpEL bytecode compiler is unsupported in native images.
 */
final class SpelUsageCheck extends AbstractArchUnitGraalVmCheck {

    SpelUsageCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-SPEL-001",
                "Programmatic SpEL expression parsing relies on reflection with no AOT visibility",
                GraalVmCategory.SPRING_AOT,
                "MEDIUM",
                "Detects calls to ExpressionParser.parseExpression / parseRaw (SpEL programmatic API); runtime-parsed expressions use reflection to access object properties that is not visible to native-image, and the SpEL bytecode compiler is unsupported in native images.",
                "Replace programmatic SpEL with direct Java code or annotation-driven evaluation (@PreAuthorize, @Value, @Cacheable) that Spring AOT processes statically. If programmatic SpEL is required, register all reflectively accessed types under reflection in reachability-metadata.json.",
                "https://docs.spring.io/spring-framework/reference/core/aot.html"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("a SpEL expression is parsed at run time") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        String name = target.getName();
                        if (!"parseExpression".equals(name) && !"parseRaw".equals(name)) {
                            return false;
                        }
                        return target.getOwner().isAssignableTo("org.springframework.expression.ExpressionParser");
                    }
                })
                .as("Classes should not parse SpEL expressions at run time without reflection metadata");
    }
}

/**
 * Flags {@code MethodHandles.Lookup} lookup methods ({@code findVirtual}, {@code findStatic},
 * {@code findConstructor}, {@code unreflect*}, etc.). Non-constant method handles require reflection
 * metadata for the target members that is not visible to the existing REFLECT checks.
 */
final class MethodHandleUsageCheck extends AbstractArchUnitGraalVmCheck {

    private static final Set<String> LOOKUP_METHODS = Set.of(
            "findVirtual",
            "findStatic",
            "findConstructor",
            "findSpecial",
            "findGetter",
            "findSetter",
            "findStaticGetter",
            "findStaticSetter",
            "unreflect",
            "unreflectConstructor",
            "unreflectGetter",
            "unreflectSetter",
            "unreflectSpecial",
            "unreflectVarHandle",
            "findVarHandle",
            "findStaticVarHandle");

    MethodHandleUsageCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-MH-001",
                "Non-constant MethodHandle lookups may need reflection metadata",
                GraalVmCategory.REFLECTION,
                "MEDIUM",
                "Detects calls to MethodHandles.Lookup.findVirtual/findStatic/findConstructor/unreflect* and related lookup methods; non-constant method handles require reflection metadata for the target members that is not visible to the existing REFLECT checks.",
                "Register the target members under reflection in reachability-metadata.json so native-image retains the necessary member descriptors. For compile-time-constant handles, native-image may fold the lookup automatically.",
                "https://www.graalvm.org/latest/reference-manual/native-image/metadata/"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("a MethodHandle lookup is performed") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        return LOOKUP_METHODS.contains(target.getName())
                                && "java.lang.invoke.MethodHandles$Lookup"
                                        .equals(target.getOwner().getName());
                    }
                })
                .as("Classes should not perform MethodHandle lookups without reflection metadata");
    }
}

/**
 * Flags calls to {@code Security.addProvider} / {@code insertProviderAt} and application classes
 * that extend {@code java.security.Provider}. Custom or third-party providers rely on
 * reflection-based service registration that is invisible to native-image.
 */
final class SecurityProviderCheck extends AbstractArchUnitGraalVmCheck {

    SecurityProviderCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-SEC-001",
                "Custom security providers may not initialize correctly in native images",
                GraalVmCategory.SECURITY_PROVIDERS,
                "MEDIUM",
                "Detects calls to Security.addProvider / Security.insertProviderAt and application classes that extend java.security.Provider; custom or third-party providers (e.g. BouncyCastle) typically rely on reflection-based service registration that is invisible to native-image.",
                "Register the provider and all its service implementations under reflection in reachability-metadata.json. Many providers (BouncyCastle, Conscrypt) publish a native-image integration guide or companion module.",
                "https://www.graalvm.org/latest/reference-manual/native-image/metadata/"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("a custom security provider is registered") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        String name = target.getName();
                        return ("addProvider".equals(name) || "insertProviderAt".equals(name))
                                && "java.security.Security"
                                        .equals(target.getOwner().getName());
                    }
                })
                .orShould()
                .beAssignableTo("java.security.Provider")
                .as("Classes should not use custom security providers without native-image reflection configuration");
    }
}

/**
 * Flags JMX usage: {@code ManagementFactory.getPlatformMBeanServer} and
 * {@code MBeanServer.registerMBean}. JMX is disabled by default in native images and requires
 * {@code --enable-monitoring=jmxserver} plus additional metadata.
 */
final class JmxUsageCheck extends AbstractArchUnitGraalVmCheck {

    JmxUsageCheck() {
        super(
                new GraalVmCheckDefinition(
                        "GRAAL-JMX-001",
                        "JMX usage requires --enable-monitoring in the native image",
                        GraalVmCategory.JMX,
                        "LOW",
                        "Detects calls to ManagementFactory.getPlatformMBeanServer and MBeanServer.registerMBean; JMX is disabled by default in native images and requires --enable-monitoring=jmxserver plus MBean reflection metadata.",
                        "Add --enable-monitoring=jmxserver to the native-image build arguments and register all MBean interfaces and implementations under reflection in reachability-metadata.json.",
                        "https://www.graalvm.org/latest/reference-manual/native-image/guides/build-and-run-native-executable-with-remote-jmx/"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(
                        new DescribedPredicate<JavaMethodCall>("JMX server is obtained or an MBean is registered") {
                            @Override
                            public boolean test(JavaMethodCall call) {
                                MethodCallTarget target = call.getTarget();
                                String name = target.getName();
                                if ("getPlatformMBeanServer".equals(name)
                                        && "java.lang.management.ManagementFactory"
                                                .equals(target.getOwner().getName())) {
                                    return true;
                                }
                                return "registerMBean".equals(name)
                                        && target.getOwner().isAssignableTo("javax.management.MBeanServer");
                            }
                        })
                .as("Classes should not use JMX without native-image monitoring configuration");
    }
}

/**
 * Flags application classes assignable to {@code javax.management.DynamicMBean} (which also matches
 * Model MBeans, since {@code javax.management.modelmbean.ModelMBean} extends {@code DynamicMBean}),
 * other than classes based on the JDK's {@code javax.management.StandardMBean} wrapper. Native-image's
 * JMX support only covers MXBeans and standard (interface-naming-convention) MBeans; dynamic and model
 * MBeans define their management interface at run time, which the closed-world analysis cannot see.
 */
final class JmxDynamicMBeanCheck implements GraalVmCheck {

    private static final GraalVmCheckDefinition DEFINITION = new GraalVmCheckDefinition(
            "GRAAL-JMX-002",
            "Dynamic/model MBeans are not supported by native-image JMX",
            GraalVmCategory.JMX,
            "HIGH",
            "Detects application classes assignable to javax.management.DynamicMBean (including Model MBeans, since ModelMBean extends DynamicMBean), other than classes based on the JDK's StandardMBean wrapper. GraalVM's native-image JMX support only covers MXBeans and standard (interface-naming-convention) MBeans; dynamic and model MBeans are unsupported because they define their management interface at run time.",
            "Replace the dynamic/model MBean with a standard MBean (a FooMBean interface plus a Foo implementation, or javax.management.StandardMBean composition) or an MXBean; both work with --enable-monitoring=jmxserver. There is no metadata registration that makes a dynamic or model MBean work in a native image.",
            "https://www.graalvm.org/latest/reference-manual/native-image/guides/build-and-run-native-executable-with-remote-jmx/");

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
                // StandardMBean itself implements DynamicMBean, so subclasses of the JDK's supported
                // StandardMBean wrapper are deliberately excluded here.
                boolean isDynamicMBean = javaClass.isAssignableTo("javax.management.DynamicMBean")
                        && !javaClass.isAssignableTo("javax.management.StandardMBean");
                if (isDynamicMBean) {
                    count++;
                    if (samples.size() < GraalVmCheckSupport.maxSampleOccurrences()) {
                        samples.add(GraalVmCheckSupport.detail(
                                javaClass.getName() + " is assignable to javax.management.DynamicMBean"));
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

/**
 * Flags application classes that depend on {@link java.lang.foreign.Linker} to build native
 * downcall handles or upcall stubs. Foreign Function &amp; Memory down/upcalls reach native symbols
 * that are invisible to the closed-world analysis and must be described under {@code foreign} in
 * {@code reachability-metadata.json}. The check matches the {@code Linker} type by name, so it
 * works even when BootUI itself runs on a JDK without the Foreign Function API.
 */
final class ForeignFunctionUsageCheck extends AbstractArchUnitGraalVmCheck {

    ForeignFunctionUsageCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-FFM-001",
                "Foreign Function downcalls/upcalls may need foreign metadata in native images",
                GraalVmCategory.NATIVE_ACCESS,
                "LOW",
                "Detects application classes that depend on java.lang.foreign.Linker to create native downcall handles or upcall stubs; Foreign Function & Memory down/upcalls reach native symbols that must be registered under foreign in reachability-metadata.json and are otherwise unreachable in a native image. Pure heap/off-heap MemorySegment or Arena usage that never touches Linker does not require this metadata and is not flagged.",
                "Register the native down/upcall descriptors under foreign in reachability-metadata.json, or confine native interop behind a boundary that can be described for the native image.",
                "https://www.graalvm.org/latest/reference-manual/native-image/metadata/"));
    }

    /**
     * Matches the {@code java.lang.foreign.Linker} interface (and its nested types) by fully
     * qualified name. Extracted so the matching logic can be unit-tested on a JDK that predates the
     * Foreign Function &amp; Memory API.
     */
    static boolean isForeignLinkerClass(String name) {
        return "java.lang.foreign.Linker".equals(name) || name.startsWith("java.lang.foreign.Linker$");
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .dependOnClassesThat(new DescribedPredicate<JavaClass>("the Foreign Function Linker") {
                    @Override
                    public boolean test(JavaClass javaClass) {
                        return isForeignLinkerClass(javaClass.getName());
                    }
                })
                .as("Classes should not use the Foreign Function Linker without native-image foreign metadata");
    }
}
