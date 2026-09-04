package io.github.jdubois.bootui.engine.archunit;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.JavaWildcardType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Kotlin-awareness for the ArchUnit-based advisors (Architecture and REST API), so a Kotlin host
 * application is analysed on its own terms instead of through the Java shape of its bytecode.
 *
 * <p>Everything here is decided from <strong>names recorded in the bytecode</strong> — the
 * {@code kotlin.Metadata} annotation, the {@code kotlin.coroutines.Continuation} parameter type, and
 * {@code kotlin.Unit} — so {@code bootui-engine} keeps no compile-time or runtime dependency on the
 * Kotlin standard library and behaves identically when it is absent. That matters twice over: the
 * engine must stay framework- and library-neutral for all three adapters, and a Java-only host
 * application must never pay for Kotlin support.
 *
 * <p>Two concerns are covered:
 *
 * <ul>
 *   <li><strong>Compiler-generated noise.</strong> The Kotlin compiler emits classes the developer
 *       never wrote ({@code FooKt} file facades, {@code Companion}, {@code DefaultImpls},
 *       {@code WhenMappings}) and members that are not part of the source ({@code foo$default}
 *       bridges, {@code getFoo$annotations}, and the {@code componentN}/{@code copy} accessors of a
 *       {@code data class}). Reporting those as findings would be noise nobody can act on.</li>
 *   <li><strong>Suspending functions.</strong> A {@code suspend fun} compiles to a method with a
 *       trailing {@code Continuation<? super T>} parameter and an erased {@code Object} return type;
 *       the declared result type {@code T} only exists inside that parameter's generic signature (and
 *       is {@code kotlin.Unit} for a function that declares no result). Advisors that reason about a
 *       method's parameters or return type therefore have to unwrap it, or they judge every
 *       suspending function against a signature the developer never wrote.</li>
 * </ul>
 */
public final class KotlinBytecode {

    /** Marker the Kotlin compiler stamps on every class it produces. */
    private static final String METADATA_ANNOTATION = "kotlin.Metadata";

    /** Trailing parameter type the compiler appends to every {@code suspend fun}. */
    public static final String CONTINUATION_TYPE = "kotlin.coroutines.Continuation";

    /** Kotlin's {@code Unit}, the result type of a function that declares no return value. */
    public static final String UNIT_TYPE = "kotlin.Unit";

    /** {@code kotlin.Metadata#k} value identifying a multi-function file facade such as {@code FooKt}. */
    private static final int FILE_FACADE_KIND = 2;

    /** Nested classes the Kotlin compiler synthesizes; none of them exist in the source file. */
    private static final List<String> GENERATED_NESTED_CLASS_NAMES =
            List.of("Companion", "DefaultImpls", "WhenMappings");

    /** Destructuring accessors generated for a {@code data class}. */
    private static final Pattern DATA_CLASS_COMPONENT = Pattern.compile("component\\d+");

    private KotlinBytecode() {}

    /** Whether this class was produced by the Kotlin compiler. */
    public static boolean isKotlinClass(JavaClass type) {
        try {
            return type.isAnnotatedWith(METADATA_ANNOTATION);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /**
     * Whether this class is a Kotlin compiler artifact rather than a type the developer declared:
     * a {@code FooKt} file facade, a {@code Companion} holder, an interface's {@code DefaultImpls},
     * or a {@code WhenMappings} switch table. None of them can be changed by the author, so rules
     * that judge a class's own shape must skip them.
     */
    public static boolean isCompilerGenerated(JavaClass type) {
        if (!isKotlinClass(type)) {
            return false;
        }
        try {
            if (GENERATED_NESTED_CLASS_NAMES.contains(type.getSimpleName())) {
                return true;
            }
            return isFileFacade(type);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /**
     * Whether this member is compiler-generated: a javac/Kotlin synthetic or bridge method, a Kotlin
     * {@code $default} / {@code $annotations} accessor, or a {@code data class} {@code componentN} /
     * {@code copy} function.
     */
    public static boolean isCompilerGenerated(JavaMember member) {
        try {
            if (member.getModifiers().contains(JavaModifier.SYNTHETIC)
                    || member.getModifiers().contains(JavaModifier.BRIDGE)) {
                return true;
            }
            if (!isKotlinClass(member.getOwner())) {
                return false;
            }
            String name = member.getName();
            // Kotlin mangles every generated member it does not want callable from source with a '$'
            // (foo$default, getFoo$annotations, access$getBar$p, …); '$' is not a legal Kotlin identifier
            // character, so no author-written member can collide with this.
            return name.indexOf('$') >= 0
                    || "copy".equals(name)
                    || DATA_CLASS_COMPONENT.matcher(name).matches();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /** The class's methods with the compiler-generated ones removed. */
    public static List<JavaMethod> declaredMethods(JavaClass type) {
        List<JavaMethod> methods = new ArrayList<>();
        for (JavaMethod method : type.getMethods()) {
            if (!isCompilerGenerated(method)) {
                methods.add(method);
            }
        }
        return methods;
    }

    /** The class's fields with the compiler-generated ones removed. */
    public static List<JavaField> declaredFields(JavaClass type) {
        List<JavaField> fields = new ArrayList<>();
        for (JavaField field : type.getFields()) {
            if (!isCompilerGenerated(field)) {
                fields.add(field);
            }
        }
        return fields;
    }

    /** The class's constructors with the compiler-generated ones removed. */
    public static List<JavaConstructor> declaredConstructors(JavaClass type) {
        List<JavaConstructor> constructors = new ArrayList<>();
        for (JavaConstructor constructor : type.getConstructors()) {
            if (!isCompilerGenerated(constructor)) {
                constructors.add(constructor);
            }
        }
        return constructors;
    }

    /** Whether this method is a Kotlin {@code suspend fun}, recognised by its trailing continuation. */
    public static boolean isSuspendFunction(JavaMethod method) {
        try {
            List<JavaClass> parameterTypes = method.getRawParameterTypes();
            return !parameterTypes.isEmpty()
                    && CONTINUATION_TYPE.equals(
                            parameterTypes.get(parameterTypes.size() - 1).getName());
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /**
     * The parameters the developer declared: the trailing {@code Continuation} of a suspending
     * function is dropped, every other method is returned unchanged.
     */
    public static List<JavaParameter> declaredParameters(JavaMethod method) {
        List<JavaParameter> parameters = new ArrayList<>(method.getParameters());
        if (!parameters.isEmpty() && isSuspendFunction(method)) {
            parameters.remove(parameters.size() - 1);
        }
        return parameters;
    }

    /**
     * The declared result type {@code T} of a {@code suspend fun}, read from its
     * {@code Continuation<? super T>} parameter. Empty for a non-suspending method, and empty when
     * the class was compiled without a generic signature to read.
     */
    public static Optional<JavaType> suspendResultType(JavaMethod method) {
        if (!isSuspendFunction(method)) {
            return Optional.empty();
        }
        try {
            List<JavaParameter> parameters = method.getParameters();
            JavaType continuation = parameters.get(parameters.size() - 1).getType();
            if (!(continuation instanceof JavaParameterizedType parameterized)) {
                return Optional.empty();
            }
            List<JavaType> arguments = parameterized.getActualTypeArguments();
            if (arguments.isEmpty()) {
                return Optional.empty();
            }
            JavaType argument = arguments.get(0);
            // The compiler always writes Continuation<? super T>, so the result type is the wildcard's
            // lower bound; a plain type argument is accepted too, for hand-written continuations.
            if (argument instanceof JavaWildcardType wildcard) {
                List<JavaType> lowerBounds = wildcard.getLowerBounds();
                return lowerBounds.isEmpty() ? Optional.empty() : Optional.of(lowerBounds.get(0));
            }
            return Optional.of(argument);
        } catch (RuntimeException | LinkageError ex) {
            return Optional.empty();
        }
    }

    /** Whether the named type is Kotlin's {@code Unit}, the equivalent of a {@code void} return. */
    public static boolean isUnit(String typeName) {
        return UNIT_TYPE.equals(typeName);
    }

    private static boolean isFileFacade(JavaClass type) {
        for (var annotation : type.getAnnotations()) {
            if (!METADATA_ANNOTATION.equals(annotation.getRawType().getName())) {
                continue;
            }
            Object kind = annotation.get("k").orElse(null);
            if (kind instanceof Integer kindValue) {
                return kindValue == FILE_FACADE_KIND;
            }
            // A metadata annotation without a readable kind: fall back to the compiler's naming scheme.
            return type.getSimpleName().endsWith("Kt");
        }
        return false;
    }
}
