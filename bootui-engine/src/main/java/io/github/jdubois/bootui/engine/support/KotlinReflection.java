package io.github.jdubois.bootui.engine.support;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Kotlin-awareness for the advisors that read a host application through {@code java.lang.reflect}
 * rather than through bytecode, so a Kotlin class is judged on what its author wrote instead of on the
 * shape the compiler gave it.
 *
 * <p>This is the reflection counterpart of the ArchUnit-side {@code KotlinBytecode}, and follows the same
 * two rules. Everything is decided from <strong>names</strong> — the runtime-retained
 * {@code kotlin.Metadata} annotation — so {@code bootui-engine} keeps no compile-time or runtime
 * dependency on the Kotlin standard library and behaves identically when it is absent. And every lookup
 * is guarded, because reflection over an application's classes can fail in ways that must never abort a
 * scan.
 */
public final class KotlinReflection {

    /** Marker the Kotlin compiler stamps on every class it produces. */
    private static final String METADATA_ANNOTATION = "kotlin.Metadata";

    private KotlinReflection() {}

    /** Whether this class was produced by the Kotlin compiler. */
    public static boolean isKotlinClass(Class<?> type) {
        if (type == null) {
            return false;
        }
        try {
            for (Annotation annotation : type.getAnnotations()) {
                if (METADATA_ANNOTATION.equals(annotation.annotationType().getName())) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /**
     * Whether the named field is the backing field of a Kotlin property rather than a field the author
     * exposed, recognised by the accessor pair the compiler generates alongside it.
     *
     * <p>Kotlin has no notion of a public field. A {@code lateinit var} is a property — read and written
     * through {@code getX()} / {@code setX()} in every language that consumes it — but the compiler must
     * leave its backing field public so the initialisation check can run from outside the class. Judging
     * that field as a public field reports an encapsulation break the author cannot fix and did not make.
     *
     * <p>The accessor pair is the discriminator rather than an approximation of one: a genuinely exposed
     * field, written {@code @JvmField var}, is emitted with no accessors at all, so it stays visible to
     * the rule exactly as a Java public field would.
     */
    public static boolean isAccessorBackedProperty(Class<?> owner, String fieldName) {
        if (owner == null || fieldName == null || fieldName.isEmpty() || !isKotlinClass(owner)) {
            return false;
        }
        try {
            Field field = owner.getDeclaredField(fieldName);
            if (Modifier.isStatic(field.getModifiers())) {
                return false;
            }
            return hasGetter(owner, field) && hasSetter(owner, field);
        } catch (NoSuchFieldException | RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static boolean hasGetter(Class<?> owner, Field field) {
        String name = field.getName();
        // A property already named "isActive" keeps that name as its getter instead of gaining a "get".
        if (isPrefixed(name)) {
            return isAccessor(owner, name, field.getType());
        }
        return isAccessor(owner, "get" + capitalize(name), field.getType());
    }

    private static boolean hasSetter(Class<?> owner, Field field) {
        String name = field.getName();
        // ... and its setter drops that prefix: "isActive" is written through setActive.
        String suffix = isPrefixed(name) ? name.substring(2) : capitalize(name);
        return isAccessor(owner, "set" + suffix, Void.TYPE, field.getType());
    }

    /** Whether the name is the Kotlin {@code isXxx} boolean-property spelling. */
    private static boolean isPrefixed(String name) {
        return name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2));
    }

    private static String capitalize(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static boolean isAccessor(Class<?> owner, String name, Class<?> returnType, Class<?>... parameters) {
        try {
            Method method = owner.getDeclaredMethod(name, parameters);
            return method.getReturnType().equals(returnType)
                    && !Modifier.isStatic(method.getModifiers())
                    && !method.isSynthetic();
        } catch (NoSuchMethodException | RuntimeException | LinkageError ex) {
            return false;
        }
    }
}
