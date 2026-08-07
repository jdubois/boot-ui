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
            "getConstructors",
            "getRecordComponents",
            "getPermittedSubclasses",
            "getSigners",
            "getNestMembers",
            "getClasses",
            "getDeclaredClasses",
            "arrayType");

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
                "Detects calls to reflection APIs that require metadata when their targets are not constant (Class.forName/arrayType/member lookups, Method.invoke, Constructor.newInstance, and Field value access). Reflective metadata accessors such as Field.getName() are intentionally ignored.",
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
                "Detects calls to Class/ClassLoader getResource/getResources/getResourceAsStream and Module.getResourceAsStream, whose resources must be embedded in the native image. Native Image can automatically register Class.getResource/getResourceAsStream only when both the receiver class and resource name are constant; runtime-computed names need metadata.",
                "Register the loaded resource paths (as globs) in reachability-metadata.json, or for application code register them with Spring's RuntimeHints (RuntimeHints.resources() via @ImportRuntimeHints) so native-image bundles them. Native-image resource URLs use the resource: scheme, so open their streams instead of treating URL.getFile() as a filesystem path.",
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
                        if (!"getResource".equals(name)
                                && !"getResources".equals(name)
                                && !"resources".equals(name)
                                && !"getResourceAsStream".equals(name)) {
                            return false;
                        }
                        JavaClass owner = target.getOwner();
                        return owner.isAssignableTo(Class.class)
                                || owner.isAssignableTo(ClassLoader.class)
                                || "java.lang.Module".equals(owner.getName());
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
            "If these types are serialized (e.g. via the JDK serialization protocol), add reflection entries with \"serializable\": true in reachability-metadata.json.",
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
 * Flags native-library loading through {@code System.loadLibrary} / {@code Runtime.loadLibrary} /
 * {@code Runtime.load}. Ordinary Unsafe memory access is supported by Native Image and must not be
 * reported as a blanket JNI concern.
 */
final class NativeAccessCheck extends AbstractArchUnitGraalVmCheck {

    NativeAccessCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-NATIVE-001",
                "Dynamically loaded native libraries need native-image review",
                GraalVmCategory.NATIVE_ACCESS,
                "LOW",
                "Detects loading of native libraries through System.load/System.loadLibrary or Runtime.load/Runtime.loadLibrary. Native Image can link or dynamically load native libraries, but their files and symbols must be available to the executable.",
                "Confirm every loaded library is linked into the native image or deployed where the executable can load it. If its native code calls back into Java through dynamic JNI lookups, collect and register those Java targets with the tracing agent.",
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
                "Detects calls to ClassLoader.loadClass, which load classes by name at run time. Native Image can resolve some constant calls, while runtime-computed names need reflection metadata or experimental run-time class loading.",
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
                "Register the accessed members under reflection in reachability-metadata.json and ensure the required module opens are configured; prefer public APIs over deep reflection.",
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
                "Add each bundle base name as a resources entry with a \"bundle\" field in reachability-metadata.json so native-image includes its locale variants.",
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

/** Flags application classes that declare {@code native} methods backed by external native code. */
final class NativeMethodCheck implements GraalVmCheck {

    private static final GraalVmCheckDefinition DEFINITION = new GraalVmCheckDefinition(
            "GRAAL-NATIVE-002",
            "Native method declarations require a loadable native implementation",
            GraalVmCategory.NATIVE_ACCESS,
            "LOW",
            "Detects application classes that declare native methods. Native Image generates the Java-to-native JNI wrappers for reachable native declarations automatically, but the backing library and symbols still have to be linked or loadable. Calls made in the opposite direction, from native code into Java through dynamic JNI lookup, require metadata that Java bytecode alone cannot identify.",
            "Ensure the native implementation is linked into or loadable by the executable. If the native code uses FindClass/GetMethodID/GetFieldID or Java callbacks, run the tracing agent over those paths and register the actual Java targets with \"jniAccessible\": true in reflection entries.",
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
 * {@code MethodHandles.Lookup.defineClass/defineHiddenClass}, Unsafe class-definition methods, CGLIB
 * {@code Enhancer}, ByteBuddy, Javassist). GraalVM's run-time class loading and predefined-classes
 * modes are experimental and carry substantial constraints, so build-time generation remains the
 * reliable default.
 */
final class RuntimeClassGenerationCheck extends AbstractArchUnitGraalVmCheck {

    RuntimeClassGenerationCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-CLASSGEN-001",
                "Runtime class generation needs experimental native-image support",
                GraalVmCategory.CLASS_GENERATION,
                "HIGH",
                "Detects runtime bytecode/class generation (ClassLoader/MethodHandles.Lookup/Unsafe defineClass methods, CGLIB, ByteBuddy, Javassist). GraalVM can enable experimental run-time class loading with -H:+RuntimeClassLoading (and optional JIT support), while the tracing agent's experimental Predefined Classes mode can replay a bounded set of previously seen classes. Both approaches need explicit build configuration and have important reachability, loading, and compatibility constraints.",
                "Prefer Spring AOT or another build-time generator, or replace generated types with statically compiled equivalents. If generation truly cannot be avoided, validate the exact workload against -H:+RuntimeClassLoading and its -H:Preserve requirements, or evaluate Predefined Classes for bytecode that is stable across runs.",
                "https://github.com/oracle/graal/blob/master/substratevm/docs/runtime-class-loading.md"));
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
                                    || owner.isAssignableTo(ClassLoader.class)
                                    || "sun.misc.Unsafe".equals(ownerName)
                                    || "jdk.internal.misc.Unsafe".equals(ownerName);
                        }
                        if ("defineAnonymousClass".equals(name)) {
                            return "sun.misc.Unsafe".equals(ownerName) || "jdk.internal.misc.Unsafe".equals(ownerName);
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
                "Detects construction of ScriptEngineManager. Native Image processes reachable ServiceLoader providers automatically, but JSR-223 engines commonly load or generate executable code dynamically and still need an engine-specific native integration.",
                "Remove runtime scripting, replace it with statically compiled application logic, or validate a specific engine's documented Native Image integration and its resource, reflection, class-loading, and native requirements.",
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
                "Add every serialized type as a reflection entry with \"serializable\": true in reachability-metadata.json (or use Spring RuntimeHints serialization registration), or prefer a format that does not need build-time registration.",
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
                "Runtime bean singleton registration cannot be transformed by Spring AOT",
                GraalVmCategory.SPRING_AOT,
                "MEDIUM",
                "Detects SingletonBeanRegistry.registerSingleton(...) calls. Spring AOT transforms bean definitions, not singleton instances registered directly with a BeanFactory, so these registrations cannot contribute generated construction code or reachability hints.",
                "Register a bean definition through @Bean/@Component, BeanDefinitionRegistry, ImportBeanDefinitionRegistrar, or Spring Framework 7's AOT-supported BeanRegistrar API. Note: a runtime singleton still exists; the risk is missing AOT-generated construction and reachability support.",
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
                                if (SpringAotGeneratedCode.isGenerated(call.getOriginOwner())) {
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
 * Flags environment-sensitive conditions on application configuration and bean methods. Deliberate
 * {@code @AutoConfiguration} classes are excluded because condition-driven auto-configuration is the
 * framework's intended AOT model.
 */
final class SpringAotConditionedBeansCheck implements GraalVmCheck {

    private static final GraalVmCheckDefinition DEFINITION = new GraalVmCheckDefinition(
            "SPRING-AOT-003",
            "Environment-sensitive bean conditions freeze selection at AOT build time",
            GraalVmCategory.SPRING_AOT,
            "MEDIUM",
            "Detects @Profile, @ConditionalOnProperty, @ConditionalOnBooleanProperty, custom @Conditional, or property-only @ConditionalOnExpression on application configuration/components and @Bean methods. Spring AOT evaluates these conditions at build time; deliberate @AutoConfiguration classes and classpath-only Spring Boot conditions are excluded.",
            "Ensure the profiles and properties active during the AOT build (native-image compilation) match the intended production configuration, or restructure the configuration to use explicit build-time selection rather than runtime conditions.",
            "https://docs.spring.io/spring-framework/reference/core/aot.html");

    @Override
    public GraalVmCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public GraalVmFindingDto evaluate(GraalVmContext context) {
        return SpringAotConditionSupport.evaluate(context, DEFINITION, false);
    }
}

/** Flags bean references in {@code @ConditionalOnExpression} separately at HIGH severity. */
final class SpringAotBeanExpressionCheck implements GraalVmCheck {

    private static final GraalVmCheckDefinition DEFINITION = new GraalVmCheckDefinition(
            "SPRING-AOT-005",
            "Bean-referencing @ConditionalOnExpression can initialize beans too early",
            GraalVmCategory.SPRING_AOT,
            "HIGH",
            "Detects bean references in @ConditionalOnExpression on Spring components and @Bean methods. The expression is evaluated early, and a referenced bean can initialize before post-processing such as configuration-properties binding.",
            "Replace bean-referencing SpEL with property/class conditions that Spring AOT can evaluate without instantiating beans. If the expression is unavoidable, ensure it references no beans and uses build-time-stable inputs.",
            "https://docs.spring.io/spring-boot/api/java/org/springframework/boot/autoconfigure/condition/ConditionalOnExpression.html");

    @Override
    public GraalVmCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public GraalVmFindingDto evaluate(GraalVmContext context) {
        return SpringAotConditionSupport.evaluate(context, DEFINITION, true);
    }
}

/** Shared classifier for the two stable Spring AOT condition findings. */
final class SpringAotConditionSupport {

    private static final List<String> SPRING_COMPONENT_ANNOTATIONS = List.of(
            "org.springframework.context.annotation.Configuration",
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController");
    private static final String BEAN_ANNOTATION = "org.springframework.context.annotation.Bean";
    private static final String AUTO_CONFIGURATION = "org.springframework.boot.autoconfigure.AutoConfiguration";
    private static final String PROFILE = "org.springframework.context.annotation.Profile";
    private static final String CONDITIONAL = "org.springframework.context.annotation.Conditional";
    private static final String CONDITIONAL_ON_PROPERTY =
            "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty";
    private static final String CONDITIONAL_ON_BOOLEAN_PROPERTY =
            "org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty";
    private static final String CONDITIONAL_ON_EXPRESSION =
            "org.springframework.boot.autoconfigure.condition.ConditionalOnExpression";
    private static final String BOOT_CONDITION_PACKAGE = "org.springframework.boot.autoconfigure.condition.";

    private SpringAotConditionSupport() {}

    static GraalVmFindingDto evaluate(
            GraalVmContext context, GraalVmCheckDefinition definition, boolean beanExpressions) {
        try {
            List<String> samples = new ArrayList<>();
            int count = 0;
            for (JavaClass javaClass : context.classes()) {
                boolean autoConfiguration = javaClass.isAnnotatedWith(AUTO_CONFIGURATION);
                if (isSpringComponent(javaClass) && matchesClass(javaClass, autoConfiguration, beanExpressions)) {
                    count++;
                    addSample(
                            samples,
                            javaClass.getName()
                                    + (beanExpressions
                                            ? " has a bean-referencing @ConditionalOnExpression"
                                            : " is a Spring component with an AOT-time condition"));
                }
                for (JavaMethod method : javaClass.getMethods()) {
                    if (method.isAnnotatedWith(BEAN_ANNOTATION)
                            && matchesMethod(method, autoConfiguration, beanExpressions)) {
                        count++;
                        addSample(
                                samples,
                                javaClass.getName() + "." + method.getName()
                                        + (beanExpressions
                                                ? " @Bean method has a bean-referencing @ConditionalOnExpression"
                                                : " @Bean method has an AOT-time condition"));
                    }
                }
            }
            return count == 0
                    ? GraalVmCheckSupport.ok(definition)
                    : GraalVmCheckSupport.review(definition, count, samples);
        } catch (RuntimeException | LinkageError ex) {
            return GraalVmCheckSupport.error(definition, "Check could not be evaluated: " + ex.getMessage());
        }
    }

    private static boolean matchesClass(JavaClass javaClass, boolean autoConfiguration, boolean beanExpressions) {
        return matches(
                javaClass.getAnnotations(), expressionReferencesBean(javaClass), autoConfiguration, beanExpressions);
    }

    private static boolean matchesMethod(JavaMethod method, boolean autoConfiguration, boolean beanExpressions) {
        return matches(method.getAnnotations(), expressionReferencesBean(method), autoConfiguration, beanExpressions);
    }

    private static boolean matches(
            Iterable<? extends JavaAnnotation<?>> annotations,
            boolean referencesBean,
            boolean autoConfiguration,
            boolean beanExpressions) {
        if (beanExpressions) {
            return referencesBean;
        }
        return !autoConfiguration && !referencesBean && hasRelevantCondition(annotations);
    }

    private static boolean hasRelevantCondition(Iterable<? extends JavaAnnotation<?>> annotations) {
        for (JavaAnnotation<?> annotation : annotations) {
            JavaClass annotationType = annotation.getRawType();
            String name = annotationType.getName();
            if (PROFILE.equals(name)
                    || CONDITIONAL.equals(name)
                    || CONDITIONAL_ON_PROPERTY.equals(name)
                    || CONDITIONAL_ON_BOOLEAN_PROPERTY.equals(name)
                    || CONDITIONAL_ON_EXPRESSION.equals(name)
                    || annotationType.isMetaAnnotatedWith(PROFILE)
                    || annotationType.isMetaAnnotatedWith(CONDITIONAL_ON_PROPERTY)
                    || annotationType.isMetaAnnotatedWith(CONDITIONAL_ON_BOOLEAN_PROPERTY)
                    || annotationType.isMetaAnnotatedWith(CONDITIONAL_ON_EXPRESSION)) {
                return true;
            }
            if (!name.startsWith(BOOT_CONDITION_PACKAGE) && annotationType.isMetaAnnotatedWith(CONDITIONAL)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSpringComponent(JavaClass javaClass) {
        for (String annotation : SPRING_COMPONENT_ANNOTATIONS) {
            if (javaClass.isAnnotatedWith(annotation) || javaClass.isMetaAnnotatedWith(annotation)) {
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
                .map(SpringAotConditionSupport::containsBeanReference)
                .orElse(false);
    }

    private static boolean expressionReferencesBean(JavaMethod method) {
        return method.tryGetAnnotationOfType(CONDITIONAL_ON_EXPRESSION)
                .flatMap(annotation -> annotation.get("value"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(SpringAotConditionSupport::containsBeanReference)
                .orElse(false);
    }

    private static void addSample(List<String> samples, String sample) {
        if (samples.size() < GraalVmCheckSupport.maxSampleOccurrences()) {
            samples.add(GraalVmCheckSupport.detail(sample));
        }
    }

    private static boolean containsBeanReference(String expression) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int i = 0; i < expression.length() - 1; i++) {
            char current = expression.charAt(i);
            if (current == '\\' && doubleQuoted) {
                i++;
                continue;
            }
            if (current == '\'' && !doubleQuoted) {
                if (singleQuoted && expression.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    singleQuoted = !singleQuoted;
                }
                continue;
            }
            if (current == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (!singleQuoted
                    && !doubleQuoted
                    && current == '@'
                    && Character.isJavaIdentifierStart(expression.charAt(i + 1))) {
                return true;
            }
        }
        return false;
    }
}

/**
 * Flags runtime construction of {@code AnnotationConfigApplicationContext} or
 * {@code GenericApplicationContext}, and {@code SpringApplicationBuilder.child()} calls. Secondary
 * contexts created at application run time do not use the main context's generated initializer, so
 * their beans and runtime hints need explicit AOT handling.
 */
final class RuntimeApplicationContextCheck extends AbstractArchUnitGraalVmCheck {

    RuntimeApplicationContextCheck() {
        super(new GraalVmCheckDefinition(
                "SPRING-AOT-004",
                "Programmatic ApplicationContext creation requires AOT review",
                GraalVmCategory.SPRING_AOT,
                "HIGH",
                "Detects construction of AnnotationConfigApplicationContext or GenericApplicationContext and SpringApplicationBuilder.child() calls outside Spring-generated AOT code. Contexts created at application run time do not use the main context's generated initializer. GenericApplicationContext can participate in build-time AOT processing through refreshForAotProcessing, so intentional AOT harnesses require manual review rather than an absolute failure verdict.",
                "Consolidate configuration into the main AOT-processed application context, or include it statically with @Import/@ImportResource. If this is build tooling, call refreshForAotProcessing and keep it out of runtime application paths.",
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
                                if (SpringAotGeneratedCode.isGenerated(call.getOriginOwner())) {
                                    return false;
                                }
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

/** Identifies Spring's generated AOT bytecode without relying only on a naming convention. */
final class SpringAotGeneratedCode {

    private static final String GENERATED = "org.springframework.aot.generate.Generated";

    private SpringAotGeneratedCode() {}

    static boolean isGenerated(JavaClass javaClass) {
        return javaClass.getName().endsWith("__BeanDefinitions") || javaClass.isAnnotatedWith(GENERATED);
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
                "Detects calls to ExpressionParser.parseExpression / parseRaw (SpEL programmatic API); runtime-parsed expressions can use reflection to access object properties that are not visible to native-image, and the SpEL bytecode compiler is unsupported in native images.",
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
            "findClass",
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
                "Detects calls to MethodHandles.Lookup.findClass/findVirtual/findStatic/findConstructor/unreflect* and related lookup methods; all reflective MethodHandles.Lookup operations require target metadata unless Native Image can prove the target is constant.",
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
 * Flags calls to {@code Security.addProvider} / {@code insertProviderAt}. Merely declaring a
 * {@code Provider} subclass is not enough evidence that the provider is added at run time.
 */
final class SecurityProviderCheck extends AbstractArchUnitGraalVmCheck {

    SecurityProviderCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-SEC-001",
                "Runtime security-provider registration needs native-image review",
                GraalVmCategory.SECURITY_PROVIDERS,
                "MEDIUM",
                "Detects calls to Security.addProvider / Security.insertProviderAt. Native Image automatically analyzes security services present at build time, but adding a new provider at run time is restricted and can require provider-specific reachability and initialization support.",
                "Prefer providers configured at image build time and follow the provider's Native Image integration guide. For migration testing, review GraalVM's --future-defaults=run-time-initialize-security-providers behavior before relying on runtime registration.",
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
                .as("Classes should not add security providers at run time without native-image review");
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
                        "Detects calls to ManagementFactory.getPlatformMBeanServer and MBeanServer.registerMBean. Native-image JMX support is experimental and disabled by default; server, client, and JVM-statistics capabilities are enabled explicitly with --enable-monitoring.",
                        "Add --enable-monitoring=jmxserver (and jmxclient/jvmstat if required). Register each standard MBean interface as reflection proxy metadata (a reflection type whose value is {\"proxy\":[\"com.example.FooMBean\"]}) and register any reflectively accessed implementation members.",
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
 * Flags calls to {@link java.lang.foreign.Linker#downcallHandle} and {@code upcallStub}. Merely
 * carrying a {@code Linker} field is not evidence that foreign descriptors are needed.
 */
final class ForeignFunctionUsageCheck extends AbstractArchUnitGraalVmCheck {

    ForeignFunctionUsageCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-FFM-001",
                "Foreign Function downcalls/upcalls may need foreign metadata in native images",
                GraalVmCategory.NATIVE_ACCESS,
                "LOW",
                "Detects calls to java.lang.foreign.Linker.downcallHandle or upcallStub. These calls create native downcalls/upcalls whose FunctionDescriptor layouts may need foreign metadata. Merely referencing Linker, MemorySegment, or Arena without creating a call handle is intentionally not flagged.",
                "Register the native down/upcall descriptors under foreign in reachability-metadata.json, or confine native interop behind a boundary that can be described for the native image.",
                "https://www.graalvm.org/latest/reference-manual/native-image/metadata/"));
    }

    static boolean isForeignLinkerCall(String ownerName, String methodName) {
        return "java.lang.foreign.Linker".equals(ownerName)
                && ("downcallHandle".equals(methodName) || "upcallStub".equals(methodName));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("a foreign downcall or upcall is created") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        return isForeignLinkerCall(target.getOwner().getName(), target.getName());
                    }
                })
                .as("Classes should not use the Foreign Function Linker without native-image foreign metadata");
    }
}
