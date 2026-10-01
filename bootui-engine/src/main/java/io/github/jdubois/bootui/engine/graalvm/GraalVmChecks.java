package io.github.jdubois.bootui.engine.graalvm;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitCallTarget;
import com.tngtech.archunit.core.domain.AccessTarget.MethodCallTarget;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.ReferencedClassObject;
import com.tngtech.archunit.lang.ArchRule;
import io.github.jdubois.bootui.core.dto.GraalVmFindingDto;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    // Spring's reflection facades perform the JDK lookup or access inside Spring on a method parameter, so the
    // application's call site is the only place the target is visible. Optional-dependency probes such as
    // ClassUtils.isPresent and accessibility helpers such as ReflectionUtils.makeAccessible are deliberately absent.
    private static final Map<String, Set<String>> SPRING_REFLECTION_FACADES = Map.of(
            "org.springframework.util.ReflectionUtils",
            Set.of(
                    "findField",
                    "findMethod",
                    "getField",
                    "setField",
                    "invokeMethod",
                    "getDeclaredMethods",
                    "getAllDeclaredMethods",
                    "getUniqueDeclaredMethods",
                    "doWithFields",
                    "doWithLocalFields",
                    "doWithMethods",
                    "doWithLocalMethods",
                    "accessibleConstructor"),
            "org.springframework.util.ClassUtils",
            Set.of(
                    "forName",
                    "resolveClassName",
                    "getMethod",
                    "getMethodIfAvailable",
                    "getConstructorIfAvailable",
                    "getStaticMethod"),
            "org.springframework.beans.BeanUtils",
            Set.of(
                    "instantiateClass",
                    "findMethod",
                    "findDeclaredMethod",
                    "getPropertyDescriptors",
                    "getPropertyDescriptor",
                    "copyProperties"));

    ReflectionUsageCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-REFLECT-001",
                "Reflective API usage may need reflection metadata",
                GraalVmCategory.REFLECTION,
                "MEDIUM",
                "Detects calls to reflection APIs that require metadata when their targets are not resolved during native-image analysis (Class.forName/arrayType/member lookups, Method.invoke, Constructor.newInstance, Field value access) and the Spring facades that perform the same lookups on their arguments (ReflectionUtils, ClassUtils.forName/getMethod*, BeanUtils.instantiateClass/copyProperties/property descriptors). Reflective metadata accessors such as Field.getName(), ClassUtils.isPresent, and ReflectionUtils.makeAccessible are intentionally ignored, as is Spring AOT-generated code.",
                "Review the actual target members and existing Spring AOT or dependency hints before adding registrations in reachability-metadata.json or Spring RuntimeHints. Spring AOT covers supported framework contracts, not every reflective operation performed by a Spring-managed bean. BeanUtils.copyProperties and property-descriptor lookups find no accessors on an unregistered type, so a copy can silently do nothing; register those types for method invocation (for example with @RegisterReflection or @RegisterReflectionForBinding).",
                "https://www.graalvm.org/latest/reference-manual/native-image/metadata/"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("a reflection API method is called") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        if (SpringAotGeneratedCode.isGenerated(call.getOriginOwner())) {
                            return false;
                        }
                        MethodCallTarget target = call.getTarget();
                        String owner = target.getOwner().getName();
                        String name = target.getName();
                        Set<String> facadeMethods = SPRING_REFLECTION_FACADES.get(owner);
                        if (facadeMethods != null) {
                            return facadeMethods.contains(name);
                        }
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
                "Detects calls to Class/ClassLoader getResource/getResources/getResourceAsStream and Module.getResourceAsStream, plus Spring classpath resource handles (new ClassPathResource(...)) and ResourcePatternResolver.getResources(...) pattern lookups, whose resources must be embedded in the native image. Native Image can automatically register Class.getResource/getResourceAsStream only when both the receiver class and resource name are constant; names passed through Spring's resource abstraction are resolved later and need metadata unless Spring Boot's built-in hints (application configuration, banner, messages, logging configuration) already cover them. Pattern lookups only see resources that were embedded.",
                "Register the loaded resource paths (as globs) in reachability-metadata.json, or for application code register them with Spring's RuntimeHints (RuntimeHints.resources() via @ImportRuntimeHints) so native-image bundles them. Native-image resource URLs use the resource: scheme, so open their streams instead of treating URL.getFile() as a filesystem path.",
                "https://www.graalvm.org/latest/reference-manual/native-image/metadata/"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callCodeUnitWhere(new DescribedPredicate<JavaCall<?>>("a resource is loaded by name") {
                    @Override
                    public boolean test(JavaCall<?> call) {
                        if (SpringAotGeneratedCode.isGenerated(call.getOriginOwner())) {
                            return false;
                        }
                        CodeUnitCallTarget target = call.getTarget();
                        String name = target.getName();
                        JavaClass owner = target.getOwner();
                        if ("<init>".equals(name)) {
                            return "org.springframework.core.io.ClassPathResource".equals(owner.getName());
                        }
                        if ("getResources".equals(name)
                                && owner.isAssignableTo(
                                        "org.springframework.core.io.support.ResourcePatternResolver")) {
                            return true;
                        }
                        if (!"getResource".equals(name)
                                && !"getResources".equals(name)
                                && !"resources".equals(name)
                                && !"getResourceAsStream".equals(name)) {
                            return false;
                        }
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
                "Detects calls to ClassLoader.loadClass, which resolve classes by name. Native Image can resolve some constant calls; other lookups within the image may need reflection metadata. Loading genuinely new bytecode is a separate, release-dependent experimental capability, not a general metadata remedy.",
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
                "Detects calls to Unsafe.allocateInstance(Class) on sun.misc.Unsafe or jdk.internal.misc.Unsafe. This constructs an instance without invoking any constructor, which bypasses the construction path native-image's reachability analysis tracks; without unsafeAllocated metadata the allocation fails at run time (a MissingReflectionRegistrationError under exact reachability handling).",
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
                "Detects runtime bytecode/class generation (ClassLoader/MethodHandles.Lookup/Unsafe defineClass methods, CGLIB, ByteBuddy, Javassist). A default native image cannot define new classes at run time. GraalVM 25.0.x documents experimental runtime loading only for trivial classes; the GraalVM 25.1+ feature releases add experimental run-time class loading (-H:+RuntimeClassLoading; interpreted, with optional -H:+GraalJITCompileAtRuntime from 25.3) with documented limits such as no parallel class loading, no reloading of classes already included in the image, and no fallback for members the analysis removed from image classes (often requiring -H:Preserve=package=...).",
                "Prefer Spring AOT or another build-time generator, or replace generated types with statically compiled equivalents. If generation cannot be avoided, validate the exact workload with the experimental run-time class loading options of the GraalVM release you ship, or the agent's Predefined Classes mode for stable bytecode; neither is a general compatibility guarantee.",
                "https://github.com/oracle/graal/blob/graal-25.4.4.1.1/substratevm/docs/runtime-class-loading.md"));
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
                "Add every serialized type as a reflection entry with \"serializable\": true in reachability-metadata.json (or use Spring RuntimeHints serialization registration), or prefer a format that does not need build-time registration. Native Image also registers the exact classes named in a compile-time-constant ObjectInputFilter.Config.createFilter(\"pkg.SerializableClass;!*;\") pattern (package wildcards do not register), which restricts deserialization on the JVM as well.",
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
 * Flags classpath/component discovery operations (Spring's
 * {@code ClassPathScanningCandidateComponentProvider}, the Reflections library, or ClassGraph). The
 * closed-world native image has no ordinary runtime classpath; call sites alone do not establish
 * whether discovery happens during AOT processing or at runtime.
 */
final class RuntimeClasspathScanningCheck extends AbstractArchUnitGraalVmCheck {

    private static final Set<String> CLASSGRAPH_DISCOVERY = Set.of(
            "scan",
            "scanAsync",
            "getClasspath",
            "getClasspathFiles",
            "getClasspathURIs",
            "getClasspathURLs",
            "getModules");

    RuntimeClasspathScanningCheck() {
        super(new GraalVmCheckDefinition(
                "GRAAL-SCAN-001",
                "Classpath discovery calls require runtime versus build-time review",
                GraalVmCategory.CLASSPATH_SCANNING,
                "HIGH",
                "Detects scan/discovery operations in Spring, Reflections, and ClassGraph, excluding scanner configuration and consumption of existing results. A native image has no ordinary runtime classpath; this static scan cannot determine whether a call executes at runtime, only during AOT processing, or not at all.",
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
                        if ("findCandidateComponents".equals(name)
                                && target.getOwner()
                                        .isAssignableTo(
                                                "org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider")) {
                            return true;
                        }
                        if (target.getOwner().isAssignableTo("org.reflections.Reflections")) {
                            List<JavaClass> parameters = target.getRawParameterTypes();
                            if ("<init>".equals(name)) {
                                return !parameters.isEmpty()
                                        && ("org.reflections.Configuration"
                                                        .equals(parameters
                                                                .get(0)
                                                                .getName())
                                                || parameters.get(0).isEquivalentTo(String.class)
                                                || parameters.get(0).isEquivalentTo(Object[].class));
                            }
                            if ("scan".equals(name)) {
                                return true;
                            }
                            // Static collect discovers metadata on the classpath; stream/file overloads do not.
                            return "collect".equals(name)
                                    && (parameters.isEmpty()
                                            || "java.lang.String"
                                                    .equals(parameters.get(0).getName()));
                        }
                        return CLASSGRAPH_DISCOVERY.contains(name)
                                && target.getOwner().isAssignableTo("io.github.classgraph.ClassGraph");
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
                "Prefer an AOT-discoverable constructor or factory-method bean definition (@Bean), or Spring Framework 7's BeanRegistrar / BeanRegistrarDsl. Infrastructure can supply a custom AOT code-generation contribution. RuntimeHintsRegistrar alone cannot generate the missing bean-instantiation code; add hints separately for remaining dynamic access.",
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
            "Detects @Profile, @ConditionalOnProperty, @ConditionalOnBooleanProperty, @ConditionalOnCloudPlatform, @ConditionalOnThreading, custom @Conditional, or property-only @ConditionalOnExpression on application configuration/components and @Bean methods. Spring AOT evaluates these conditions at build time, so bean selection is frozen by the build machine's profiles, properties, environment variables, and Java version; deliberate @AutoConfiguration classes and classpath/bean-only Spring Boot conditions are excluded.",
            "Ensure the profiles, properties, and platform active during the AOT build (native-image compilation) match the intended production configuration — for example set spring.main.cloud-platform and spring.threads.virtual.enabled explicitly for the build — or restructure the configuration to use explicit build-time selection rather than runtime conditions.",
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
    static final String BEAN_ANNOTATION = "org.springframework.context.annotation.Bean";
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
    // Spring Boot conditions that read the deployment environment rather than the classpath or bean registry.
    private static final List<String> ENVIRONMENT_BOOT_CONDITIONS = List.of(
            BOOT_CONDITION_PACKAGE + "ConditionalOnCloudPlatform", BOOT_CONDITION_PACKAGE + "ConditionalOnThreading");

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
            for (String environmentCondition : ENVIRONMENT_BOOT_CONDITIONS) {
                if (environmentCondition.equals(name) || annotationType.isMetaAnnotatedWith(environmentCondition)) {
                    return true;
                }
            }
            if (!name.startsWith(BOOT_CONDITION_PACKAGE) && annotationType.isMetaAnnotatedWith(CONDITIONAL)) {
                return true;
            }
        }
        return false;
    }

    static boolean isSpringComponent(JavaClass javaClass) {
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

    static boolean containsBeanReference(String expression) {
        char quote = 0;
        for (int i = 0; i < expression.length(); i++) {
            char current = expression.charAt(i);
            if (quote != 0) {
                if (current == quote) {
                    if (i + 1 < expression.length() && expression.charAt(i + 1) == quote) {
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
                continue;
            }
            if (current == '@' || current == '&') {
                if (current == '&' && i + 1 < expression.length() && expression.charAt(i + 1) == '&') {
                    i++;
                    continue;
                }
                int next = i + 1;
                while (next < expression.length() && Character.isWhitespace(expression.charAt(next))) {
                    next++;
                }
                if (next < expression.length()) {
                    char first = expression.charAt(next);
                    if (Character.isJavaIdentifierStart(first) || first == '\'' || first == '"') {
                        return true;
                    }
                }
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
                "Programmatic SpEL expressions may require application-specific reflection hints",
                GraalVmCategory.SPRING_AOT,
                "MEDIUM",
                "Detects calls to ExpressionParser.parseExpression / parseRaw (SpEL programmatic API); runtime-parsed expressions can use reflection to access object properties that are not visible to native-image, and the SpEL bytecode compiler is unsupported in native images.",
                "Prefer direct Java code where practical. If SpEL is required, review the actual types and members accessed and register missing reflection hints. Annotation-driven expressions such as @PreAuthorize, @Value, and @Cacheable can also need application-specific hints; annotation placement alone does not guarantee coverage. Exercise the expressions in the native executable.",
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
                "Detects calls to Security.addProvider / Security.insertProviderAt. Native Image captures the provider list and order at build time, and by default new security providers cannot be registered at run time; only provider instances already present in the image can be reordered.",
                "Configure the provider statically in the build-time provider list (java.security) and follow the provider's Native Image integration guide; re-inserting an instance obtained from Security.getProvider to change the order is supported. With --future-defaults=run-time-initialize-security-providers (or all / run-time-initialize-jdk) the provider list is constructed at run time; validate that mode before relying on runtime registration.",
                "https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/JCASecurityServices/"));
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
 * Flags JMX operations whose native-image behavior depends on monitoring options or metadata: MBean
 * registration and attribute/operation access, JMX proxies, remote connector servers, and remote clients.
 * Obtaining the platform MBeanServer alone is not flagged because Native Image substitutes an in-process
 * server that works without {@code --enable-monitoring}.
 */
final class JmxUsageCheck extends AbstractArchUnitGraalVmCheck {

    private static final Set<String> MBEAN_ACCESS = Set.of("getAttribute", "getAttributes", "setAttribute", "invoke");

    JmxUsageCheck() {
        super(
                new GraalVmCheckDefinition(
                        "GRAAL-JMX-001",
                        "JMX MBeans and connectors need native-image monitoring and metadata review",
                        GraalVmCategory.JMX,
                        "LOW",
                        "Detects MBeanServer.registerMBean, MBean attribute/operation access through an MBeanServerConnection, JMX.newMBeanProxy/newMXBeanProxy, remote connector servers (JMXConnectorServerFactory.newJMXConnectorServer), and remote clients (JMXConnectorFactory.connect/newJMXConnector, ManagementFactory.newPlatformMXBeanProxy and the MBeanServerConnection overloads of getPlatformMXBean(s)). ManagementFactory.getPlatformMBeanServer() alone is not flagged: Native Image substitutes an in-process MBeanServer. Standard MBean introspection and JMX proxies are reflective, platform-bean attributes are only readable through the MBeanServer when their interface methods are registered, and without --enable-monitoring=jmxclient the remote-client ManagementFactory methods silently return null or an empty list instead of failing.",
                        "Add --enable-monitoring=jmxserver for remote management and jmxclient for outgoing connections (jvmstat for discovery). Register each standard MBean interface for reflection and as a structured proxy type such as {\"type\":{\"proxy\":[\"com.example.FooMBean\"]}}; notification-emitting proxies need the ordered interfaces [\"com.example.FooMBean\",\"javax.management.NotificationEmitter\"]. Register the interface methods of any platform MXBean whose attributes you read through the MBeanServer.",
                        "https://www.graalvm.org/latest/reference-manual/native-image/guides/build-and-run-native-executable-with-remote-jmx/"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("a JMX MBean, proxy, or connector is used") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        MethodCallTarget target = call.getTarget();
                        String name = target.getName();
                        JavaClass owner = target.getOwner();
                        String ownerName = owner.getName();
                        switch (ownerName) {
                            case "javax.management.JMX":
                                return "newMBeanProxy".equals(name) || "newMXBeanProxy".equals(name);
                            case "javax.management.remote.JMXConnectorServerFactory":
                                return "newJMXConnectorServer".equals(name);
                            case "javax.management.remote.JMXConnectorFactory":
                                return "connect".equals(name) || "newJMXConnector".equals(name);
                            case "java.lang.management.ManagementFactory":
                                if ("newPlatformMXBeanProxy".equals(name)) {
                                    return true;
                                }
                                List<JavaClass> parameters = target.getRawParameterTypes();
                                return ("getPlatformMXBean".equals(name) || "getPlatformMXBeans".equals(name))
                                        && !parameters.isEmpty()
                                        && "javax.management.MBeanServerConnection"
                                                .equals(parameters.get(0).getName());
                            default:
                                break;
                        }
                        if ("registerMBean".equals(name)) {
                            return owner.isAssignableTo("javax.management.MBeanServer");
                        }
                        return MBEAN_ACCESS.contains(name)
                                && owner.isAssignableTo("javax.management.MBeanServerConnection");
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
                "Register the native down/upcall descriptors under foreign in reachability-metadata.json, and pass --enable-native-access for the module performing restricted operations (ALL-UNNAMED for class-path code). FFM support is enabled by default in GraalVM 25 native images; metadata and native-access permission solve separate problems.",
                "https://www.graalvm.org/latest/reference-manual/native-image/native-code-interoperability/ffm-api/"));
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

/**
 * Flags bean retrieval with explicit constructor or factory-method arguments
 * ({@code BeanFactory.getBean(String|Class, Object...)} and {@code ObjectProvider.getObject(Object...)}).
 * Spring AOT generates an instance supplier per bean; explicit arguments bypass it, so the matching
 * constructor is found reflectively and supplier-based field/method injection is skipped.
 */
final class ExplicitArgumentBeanRetrievalCheck extends AbstractArchUnitGraalVmCheck {

    ExplicitArgumentBeanRetrievalCheck() {
        super(new GraalVmCheckDefinition(
                "SPRING-AOT-006",
                "Explicit-argument bean retrieval may bypass AOT instance suppliers",
                GraalVmCategory.SPRING_AOT,
                "MEDIUM",
                "Detects BeanFactory.getBean(String, Object...), BeanFactory.getBean(Class, Object...), and ObjectProvider.getObject(Object...) calls outside Spring AOT-generated code. Spring AOT translates bean creation into generated instance suppliers; creating a bean with custom arguments bypasses that supplier, so the matching constructor or factory method is introspected reflectively (hints that AOT cannot infer) and autowiring on fields and methods, which the supplier performs, is skipped. Whether a call actually passes arguments and creates a bean is not observable statically.",
                "Replace prototype beans created with custom arguments by a manual factory pattern: a regular bean whose method creates the instance with new, taking the runtime arguments and its injected collaborators. If explicit-argument retrieval must stay, use constructor injection only on the target bean and register invocation hints for the constructor or factory method that Spring selects.",
                "https://docs.spring.io/spring-framework/reference/core/aot.html#aot.bestpractices.custom-arguments"));
    }

    @Override
    ArchRule rule(GraalVmContext context) {
        return noClasses()
                .should()
                .callMethodWhere(
                        new DescribedPredicate<JavaMethodCall>("a bean is retrieved with explicit creation arguments") {
                            @Override
                            public boolean test(JavaMethodCall call) {
                                if (SpringAotGeneratedCode.isGenerated(call.getOriginOwner())) {
                                    return false;
                                }
                                MethodCallTarget target = call.getTarget();
                                List<JavaClass> parameters = target.getRawParameterTypes();
                                String name = target.getName();
                                if ("getBean".equals(name)
                                        && parameters.size() == 2
                                        && parameters.get(1).isEquivalentTo(Object[].class)
                                        && (parameters.get(0).isEquivalentTo(String.class)
                                                || parameters.get(0).isEquivalentTo(Class.class))) {
                                    return target.getOwner()
                                            .isAssignableTo("org.springframework.beans.factory.BeanFactory");
                                }
                                return "getObject".equals(name)
                                        && parameters.size() == 1
                                        && parameters.get(0).isEquivalentTo(Object[].class)
                                        && target.getOwner()
                                                .isAssignableTo("org.springframework.beans.factory.ObjectProvider");
                            }
                        })
                .as("Classes should not create beans with explicit arguments under Spring AOT");
    }
}

/**
 * Flags {@code BeanDefinitionRegistryPostProcessor} beans that Spring AOT keeps in the generated runtime
 * context: stereotype-annotated implementations and {@code @Bean} methods returning one. Such a processor
 * runs during the AOT build and again when the native executable starts, unless it is also an AOT processor
 * that Spring implicitly excludes.
 */
final class RegistryPostProcessorReplayCheck implements GraalVmCheck {

    private static final String REGISTRY_POST_PROCESSOR =
            "org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor";
    private static final String INITIALIZATION_AOT_PROCESSOR =
            "org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor";
    private static final String REGISTRATION_AOT_PROCESSOR =
            "org.springframework.beans.factory.aot.BeanRegistrationAotProcessor";

    private static final GraalVmCheckDefinition DEFINITION = new GraalVmCheckDefinition(
            "SPRING-AOT-007",
            "Bean-definition registry post-processors may run again under AOT",
            GraalVmCategory.SPRING_AOT,
            "MEDIUM",
            "Detects BeanDefinitionRegistryPostProcessor beans declared by the application (a Spring stereotype class, or a @Bean method returning one) that do not also implement BeanFactoryInitializationAotProcessor or BeanRegistrationAotProcessor, which Spring AOT implicitly excludes from the generated context. Spring AOT invokes such a processor at build time and the generated context invokes it again at run time: at best it repeats work already captured in the generated bean definitions, at worst re-registering a definition fails startup with BeanDefinitionOverrideException under Spring Boot's default spring.main.allow-bean-definition-overriding=false. Processors registered programmatically are not observed.",
            "Prefer an ImportBeanDefinitionRegistrar imported with @Import, or Spring Framework 7's BeanRegistrar, which Spring AOT processes as part of configuration parsing. If the post-processor must stay a bean, also implementing BeanFactoryInitializationAotProcessor excludes it from the runtime context; Spring then initializes it and its dependencies during AOT processing, and its contribution must reproduce any effect that the generated bean definitions do not already capture (return null only when none remains).",
            "https://docs.spring.io/spring-framework/reference/core/aot.html#aot.bestpractices.bean-registration");

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
                if (SpringAotGeneratedCode.isGenerated(javaClass)) {
                    continue;
                }
                if (!javaClass.isInterface()
                        && !javaClass.getModifiers().contains(JavaModifier.ABSTRACT)
                        && SpringAotConditionSupport.isSpringComponent(javaClass)
                        && replaysAtRuntime(javaClass)) {
                    count++;
                    addSample(
                            samples,
                            javaClass.getName() + " is a BeanDefinitionRegistryPostProcessor Spring component");
                }
                for (JavaMethod method : javaClass.getMethods()) {
                    if (method.isAnnotatedWith(SpringAotConditionSupport.BEAN_ANNOTATION)
                            && replaysAtRuntime(method.getRawReturnType())) {
                        count++;
                        addSample(
                                samples,
                                javaClass.getName() + "." + method.getName()
                                        + " @Bean method returns a BeanDefinitionRegistryPostProcessor");
                    }
                }
            }
            return count == 0
                    ? GraalVmCheckSupport.ok(DEFINITION)
                    : GraalVmCheckSupport.review(DEFINITION, count, samples);
        } catch (RuntimeException | LinkageError ex) {
            return GraalVmCheckSupport.error(DEFINITION, "Check could not be evaluated: " + ex.getMessage());
        }
    }

    private static boolean replaysAtRuntime(JavaClass type) {
        return type.isAssignableTo(REGISTRY_POST_PROCESSOR)
                && !type.isAssignableTo(INITIALIZATION_AOT_PROCESSOR)
                && !type.isAssignableTo(REGISTRATION_AOT_PROCESSOR);
    }

    private static void addSample(List<String> samples, String sample) {
        if (samples.size() < GraalVmCheckSupport.maxSampleOccurrences()) {
            samples.add(GraalVmCheckSupport.detail(sample));
        }
    }
}

/**
 * Flags Spring Cloud {@code @RefreshScope} on Spring components and {@code @Bean} methods. Spring Cloud
 * documents that context refresh is not supported for Spring AOT transformations and native images.
 */
final class RefreshScopeCheck implements GraalVmCheck {

    private static final String REFRESH_SCOPE = "org.springframework.cloud.context.config.annotation.RefreshScope";
    private static final String SCOPE = "org.springframework.context.annotation.Scope";

    private static final GraalVmCheckDefinition DEFINITION = new GraalVmCheckDefinition(
            "SPRING-AOT-008",
            "Refresh-scoped bean declarations are not supported under Spring AOT",
            GraalVmCategory.SPRING_AOT,
            "MEDIUM",
            "Detects Spring components and @Bean methods annotated or meta-annotated with Spring Cloud's @RefreshScope, or declared with @Scope(\"refresh\"). Spring Cloud does not support context refresh for Spring AOT transformations and native images and requires spring.cloud.refresh.enabled=false for them; that property also removes the auto-configured refresh scope these declarations rely on, so in-process refresh does not exist in the native executable. Whether a declaration is active in the AOT build is not observable statically.",
            "For the native build, set spring.cloud.refresh.enabled=false and do not ship refresh-scoped beans: bind their configuration at startup and restart the native executable to apply configuration changes. Refresh scope can remain in JVM deployments that still rely on it.",
            "https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/application-context-services.html#refresh-scope");

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
                if (SpringAotConditionSupport.isSpringComponent(javaClass)
                        && refreshScoped(javaClass.getAnnotations())) {
                    count++;
                    addSample(samples, javaClass.getName() + " is a @RefreshScope Spring component");
                }
                for (JavaMethod method : javaClass.getMethods()) {
                    if (method.isAnnotatedWith(SpringAotConditionSupport.BEAN_ANNOTATION)
                            && refreshScoped(method.getAnnotations())) {
                        count++;
                        addSample(samples, javaClass.getName() + "." + method.getName() + " is a @RefreshScope @Bean");
                    }
                }
            }
            return count == 0
                    ? GraalVmCheckSupport.ok(DEFINITION)
                    : GraalVmCheckSupport.review(DEFINITION, count, samples);
        } catch (RuntimeException | LinkageError ex) {
            return GraalVmCheckSupport.error(DEFINITION, "Check could not be evaluated: " + ex.getMessage());
        }
    }

    private static boolean refreshScoped(Iterable<? extends JavaAnnotation<?>> annotations) {
        for (JavaAnnotation<?> annotation : annotations) {
            JavaClass type = annotation.getRawType();
            if (REFRESH_SCOPE.equals(type.getName()) || type.isMetaAnnotatedWith(REFRESH_SCOPE)) {
                return true;
            }
            if (SCOPE.equals(type.getName())
                    && ("refresh".equals(annotation.get("value").orElse(null))
                            || "refresh".equals(annotation.get("scopeName").orElse(null)))) {
                return true;
            }
        }
        return false;
    }

    private static void addSample(List<String> samples, String sample) {
        if (samples.size() < GraalVmCheckSupport.maxSampleOccurrences()) {
            samples.add(GraalVmCheckSupport.detail(sample));
        }
    }
}

/**
 * Flags application classes that override {@code finalize()} with a non-trivial body. Native Image never
 * invokes finalizers, so cleanup placed there silently does not run.
 */
final class FinalizerCheck implements GraalVmCheck {

    private static final GraalVmCheckDefinition DEFINITION = new GraalVmCheckDefinition(
            "GRAAL-JDK-003",
            "finalize() overrides are never invoked in native images",
            GraalVmCategory.RUNTIME_BEHAVIOR,
            "MEDIUM",
            "Detects application classes, including abstract base classes, that declare a non-static, non-private void finalize() whose body does more than call super.finalize(). Native Image does not invoke finalizers, so cleanup placed there silently never runs in the native executable even though it still runs on the JVM.",
            "Release resources through an explicit lifecycle (AutoCloseable with try-with-resources, or a Spring destroy callback). Use java.lang.ref.Cleaner, or weak references with a reference queue, only as a safety net for resources a caller may forget to close.",
            "https://www.graalvm.org/jdk25/reference-manual/native-image/metadata/Compatibility/");

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
                if (javaClass.isInterface()) {
                    continue;
                }
                for (JavaMethod method : javaClass.getMethods()) {
                    if (isFinalizer(method) && !isTrivial(method)) {
                        count++;
                        if (samples.size() < GraalVmCheckSupport.maxSampleOccurrences()) {
                            samples.add(GraalVmCheckSupport.detail(javaClass.getName() + " overrides finalize()"));
                        }
                    }
                }
            }
            return count == 0
                    ? GraalVmCheckSupport.ok(DEFINITION)
                    : GraalVmCheckSupport.review(DEFINITION, count, samples);
        } catch (RuntimeException | LinkageError ex) {
            return GraalVmCheckSupport.error(DEFINITION, "Check could not be evaluated: " + ex.getMessage());
        }
    }

    private static boolean isFinalizer(JavaMethod method) {
        Set<JavaModifier> modifiers = method.getModifiers();
        return "finalize".equals(method.getName())
                && method.getRawParameterTypes().isEmpty()
                && "void".equals(method.getRawReturnType().getName())
                && !modifiers.contains(JavaModifier.STATIC)
                && !modifiers.contains(JavaModifier.PRIVATE)
                && !modifiers.contains(JavaModifier.ABSTRACT);
    }

    private static boolean isTrivial(JavaMethod method) {
        if (!method.getFieldAccesses().isEmpty()
                || !method.getConstructorCallsFromSelf().isEmpty()) {
            return false;
        }
        for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
            MethodCallTarget target = call.getTarget();
            if (!"finalize".equals(target.getName())
                    || !target.getRawParameterTypes().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}

/**
 * Flags application types bound programmatically by Jackson or Spring's HTTP clients from a method body,
 * which Spring AOT does not infer binding hints for. A target is associated with a binding call only when
 * its class literal sits on the same source line as a call overload taking a {@code Class}, and types
 * already covered by an observable binding hint are skipped.
 */
final class ProgrammaticBindingCheck implements GraalVmCheck {

    private static final Map<String, Set<String>> BINDING_APIS = Map.of(
            "com.fasterxml.jackson.databind.ObjectMapper",
                    Set.of("readValue", "readValues", "convertValue", "treeToValue", "readerFor"),
            "com.fasterxml.jackson.databind.ObjectReader", Set.of("readValue", "readValues", "treeToValue", "forType"),
            "tools.jackson.databind.ObjectMapper",
                    Set.of("readValue", "readValues", "convertValue", "treeToValue", "readerFor"),
            "tools.jackson.databind.ObjectReader", Set.of("readValue", "readValues", "treeToValue", "forType"),
            "org.springframework.web.client.RestOperations",
                    Set.of(
                            "getForObject",
                            "getForEntity",
                            "postForObject",
                            "postForEntity",
                            "patchForObject",
                            "exchange"),
            "org.springframework.web.client.RestClient$ResponseSpec", Set.of("body", "toEntity"),
            "org.springframework.web.reactive.function.client.WebClient$ResponseSpec",
                    Set.of("bodyToMono", "bodyToFlux", "toEntity", "toEntityList", "toEntityFlux"),
            "org.springframework.web.reactive.function.client.ClientResponse",
                    Set.of("bodyToMono", "bodyToFlux", "toEntity", "toEntityList"));

    private static final String REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";
    private static final String HTTP_EXCHANGE = "org.springframework.web.service.annotation.HttpExchange";
    private static final String REGISTER_FOR_BINDING =
            "org.springframework.aot.hint.annotation.RegisterReflectionForBinding";
    private static final String RUNTIME_HINTS_REGISTRAR = "org.springframework.aot.hint.RuntimeHintsRegistrar";

    private static final GraalVmCheckDefinition DEFINITION = new GraalVmCheckDefinition(
            "GRAAL-REFLECT-006",
            "Programmatically bound application types may need binding hints",
            GraalVmCategory.REFLECTION,
            "MEDIUM",
            "Detects application types passed as a class literal to Jackson (ObjectMapper/ObjectReader readValue, convertValue, treeToValue, readerFor, forType) or to Spring's RestTemplate, RestClient, and WebClient body-conversion methods inside a method body. Spring AOT infers binding hints for @RequestMapping and @HttpExchange signatures, not for these calls, so without reflection metadata for constructors, fields, and accessors a native image can bind an empty object or fail to find a creator. Types appearing in an application @RequestMapping/@HttpExchange signature, named by @RegisterReflectionForBinding, or referenced by an application RuntimeHintsRegistrar are skipped. A type counts only when its class literal is on the same source line as the call; serialization of instances, ParameterizedTypeReference/TypeReference targets, and hints in JSON metadata files are not observed.",
            "Annotate the calling class or method with @RegisterReflectionForBinding(Target.class), which also registers the types its properties expose, or register binding hints through BindingReflectionHintsRegistrar in a RuntimeHintsRegistrar. Exercise the call in the native executable before relying on it.",
            "https://docs.spring.io/spring-framework/reference/core/aot.html#aot.hints.register-reflection");

    @Override
    public GraalVmCheckDefinition definition() {
        return DEFINITION;
    }

    @Override
    public GraalVmFindingDto evaluate(GraalVmContext context) {
        try {
            Set<String> hinted = hintedTypes(context);
            List<String> samples = new ArrayList<>();
            int count = 0;
            for (JavaClass javaClass : context.classes()) {
                if (SpringAotGeneratedCode.isGenerated(javaClass)) {
                    continue;
                }
                for (JavaCodeUnit codeUnit : javaClass.getCodeUnits()) {
                    for (JavaMethodCall call : codeUnit.getMethodCallsFromSelf()) {
                        if (!isBindingCall(call.getTarget())) {
                            continue;
                        }
                        for (ReferencedClassObject literal : codeUnit.getReferencedClassObjects()) {
                            String target = literal.getValue().getName();
                            if (literal.getLineNumber() == call.getLineNumber()
                                    && context.classes().contain(target)
                                    && !hinted.contains(target)) {
                                count++;
                                if (samples.size() < GraalVmCheckSupport.maxSampleOccurrences()) {
                                    samples.add(GraalVmCheckSupport.detail(javaClass.getName() + "."
                                            + codeUnit.getName()
                                            + " binds " + target + " through "
                                            + call.getTarget().getName()
                                            + "() (" + javaClass.getSimpleName() + ".java:" + call.getLineNumber()
                                            + ")"));
                                }
                            }
                        }
                    }
                }
            }
            return count == 0
                    ? GraalVmCheckSupport.ok(DEFINITION)
                    : GraalVmCheckSupport.review(DEFINITION, count, samples);
        } catch (RuntimeException | LinkageError ex) {
            return GraalVmCheckSupport.error(DEFINITION, "Check could not be evaluated: " + ex.getMessage());
        }
    }

    private static boolean isBindingCall(MethodCallTarget target) {
        boolean takesClass = false;
        for (JavaClass parameter : target.getRawParameterTypes()) {
            if (parameter.isEquivalentTo(Class.class)) {
                takesClass = true;
                break;
            }
        }
        if (!takesClass) {
            return false;
        }
        JavaClass owner = target.getOwner();
        for (Map.Entry<String, Set<String>> api : BINDING_APIS.entrySet()) {
            if (api.getValue().contains(target.getName()) && owner.isAssignableTo(api.getKey())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> hintedTypes(GraalVmContext context) {
        Set<String> hinted = new HashSet<>();
        for (JavaClass javaClass : context.classes()) {
            addBindingAnnotationTypes(javaClass, javaClass.getAnnotations(), hinted);
            if (!javaClass.isInterface() && javaClass.isAssignableTo(RUNTIME_HINTS_REGISTRAR)) {
                for (JavaCodeUnit codeUnit : javaClass.getCodeUnits()) {
                    for (ReferencedClassObject literal : codeUnit.getReferencedClassObjects()) {
                        hinted.add(literal.getValue().getName());
                    }
                }
            }
            for (JavaMethod method : javaClass.getMethods()) {
                addBindingAnnotationTypes(null, method.getAnnotations(), hinted);
                if (isWebContract(method)) {
                    for (JavaClass involved : method.getReturnType().getAllInvolvedRawTypes()) {
                        hinted.add(involved.getName());
                    }
                    for (JavaType parameter : method.getParameterTypes()) {
                        for (JavaClass involved : parameter.getAllInvolvedRawTypes()) {
                            hinted.add(involved.getName());
                        }
                    }
                }
            }
        }
        return hinted;
    }

    private static boolean isWebContract(JavaMethod method) {
        return method.isAnnotatedWith(REQUEST_MAPPING)
                || method.isMetaAnnotatedWith(REQUEST_MAPPING)
                || method.isAnnotatedWith(HTTP_EXCHANGE)
                || method.isMetaAnnotatedWith(HTTP_EXCHANGE);
    }

    private static void addBindingAnnotationTypes(
            JavaClass annotatedType, Iterable<? extends JavaAnnotation<?>> annotations, Set<String> hinted) {
        for (JavaAnnotation<?> annotation : annotations) {
            if (!REGISTER_FOR_BINDING.equals(annotation.getRawType().getName())) {
                continue;
            }
            int before = hinted.size();
            for (String attribute : List.of("value", "classes")) {
                annotation.get(attribute).ifPresent(value -> {
                    if (value instanceof JavaClass[] classes) {
                        for (JavaClass type : classes) {
                            hinted.add(type.getName());
                        }
                    }
                });
            }
            for (String attribute : List.of("classNames")) {
                annotation.get(attribute).ifPresent(value -> {
                    if (value instanceof String[] names) {
                        hinted.addAll(List.of(names));
                    }
                });
            }
            if (hinted.size() == before && annotatedType != null) {
                hinted.add(annotatedType.getName());
            }
        }
    }
}
