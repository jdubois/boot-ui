package io.github.jdubois.bootui.engine.graalvm;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared, deterministic classifications of the imported application classes. These feed both the
 * readiness checks and the {@code reachability-metadata.json} scaffold so the panel and the
 * generated file stay consistent.
 */
final class GraalVmClassPredicates {

    private static final String SERIALIZABLE = "java.io.Serializable";
    private static final String RECORD = "java.lang.Record";
    private static final List<String> ENTITY_ANNOTATIONS = List.of(
            "jakarta.persistence.Entity",
            "javax.persistence.Entity",
            "jakarta.persistence.MappedSuperclass",
            "javax.persistence.MappedSuperclass");

    private GraalVmClassPredicates() {}

    /** Concrete application classes that implement {@link java.io.Serializable}. */
    static List<JavaClass> serializableTypes(JavaClasses classes) {
        List<JavaClass> result = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            if (isConcreteType(javaClass) && javaClass.isAssignableTo(SERIALIZABLE)) {
                result.add(javaClass);
            }
        }
        return result;
    }

    /**
     * Application types that classically need reflection metadata in a native image: concrete
     * records and serializable types, plus JPA entities and mapped superclasses (including abstract
     * persistence base types). The set is ordered and de-duplicated by name.
     */
    static List<String> reflectionCandidateTypeNames(JavaClasses classes) {
        Set<String> names = new LinkedHashSet<>();
        for (JavaClass javaClass : classes) {
            if (isPersistenceType(javaClass)
                    || (isConcreteType(javaClass)
                            && (javaClass.isAssignableTo(RECORD) || javaClass.isAssignableTo(SERIALIZABLE)))) {
                names.add(javaClass.getName());
            }
        }
        return List.copyOf(names);
    }

    static List<String> serializationCandidateTypeNames(JavaClasses classes) {
        Set<String> names = new LinkedHashSet<>();
        for (JavaClass javaClass : serializableTypes(classes)) {
            names.add(javaClass.getName());
        }
        return List.copyOf(names);
    }

    static List<String> nativeMethodTypeNames(JavaClasses classes) {
        Set<String> names = new LinkedHashSet<>();
        for (JavaClass javaClass : classes) {
            if (javaClass.getMethods().stream()
                    .anyMatch(method -> method.getModifiers().contains(JavaModifier.NATIVE))) {
                names.add(javaClass.getName());
            }
        }
        return List.copyOf(names);
    }

    private static boolean isPersistenceType(JavaClass javaClass) {
        for (String annotation : ENTITY_ANNOTATIONS) {
            if (javaClass.isAnnotatedWith(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConcreteType(JavaClass javaClass) {
        return !javaClass.isInterface()
                && !javaClass.isEnum()
                && !javaClass.isAnnotation()
                && !javaClass.getModifiers().contains(JavaModifier.ABSTRACT);
    }
}
