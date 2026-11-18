package io.github.jdubois.bootui.engine.restapi;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import io.github.jdubois.bootui.engine.archunit.ArchUnitLocations;
import io.github.jdubois.bootui.engine.archunit.ClassFileFacts;
import io.github.jdubois.bootui.engine.archunit.KotlinBytecode;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.Types;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ValidationConstraints;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Constraint evidence for boundary validation, not the separate missing-nested-cascade policy. */
final class RequestPayloadConstraints {
    private static final int MAX_DEPTH = 3;
    private static final Set<String> COLLECTIONS = Set.of(
            "java.util.List", "java.util.Set", "java.util.Collection", "java.lang.Iterable", "java.util.Optional");
    private static final Set<String> MAPS =
            Set.of("java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap");
    private static final Set<String> JDK_PARENTS = Set.of(
            "java.lang.Object",
            "java.lang.Record",
            "java.lang.Enum",
            "java.io.Serializable",
            "java.lang.Comparable",
            "java.lang.Cloneable");
    private static final Set<String> JSPECIFY_NULLABILITY =
            Set.of("org.jspecify.annotations.Nullable", "org.jspecify.annotations.NonNull");

    private final Map<String, JavaClass> annotationTypes;

    RequestPayloadConstraints(List<JavaClass> classes) {
        Map<String, JavaClass> types = new HashMap<>();
        for (JavaClass type : classes) {
            if (type.isAnnotation()) {
                types.put(type.getName(), type);
            }
        }
        annotationTypes = Map.copyOf(types);
    }

    ValidationConstraints inspect(JavaClass payload) {
        try {
            return inspect(payload, new HashSet<>(), 0);
        } catch (RuntimeException | LinkageError unavailable) {
            return ValidationConstraints.UNKNOWN;
        }
    }

    private ValidationConstraints inspect(JavaClass type, Set<String> visited, int depth) {
        String name = type.getName();
        if (!visited.add(name)) {
            return ValidationConstraints.ABSENT;
        }
        if (depth > MAX_DEPTH || !type.isFullyImported()) {
            return ValidationConstraints.UNKNOWN;
        }
        ValidationConstraints result = constraints(type.getAnnotations());
        var typeAnnotations = ArchUnitLocations.classFile(type).flatMap(ClassFileFacts::tryMemberTypeAnnotations);
        if (typeAnnotations.isEmpty()) {
            result = result.combine(ValidationConstraints.UNKNOWN);
        }
        Map<String, Set<String>> annotations = typeAnnotations.orElse(Map.of());
        for (JavaField field : KotlinBytecode.declaredFields(type)) {
            if (field.getModifiers().contains(JavaModifier.STATIC)) {
                continue;
            }
            String key = "field:" + field.getName();
            result = result.combine(constraints(field.getAnnotations()));
            result = result.combine(typeUseConstraints(annotations.getOrDefault(key, Set.of())));
            result = result.combine(
                    cascade(field.getType(), field.isAnnotatedWith(Types.VALID), annotations, key, "", visited, depth));
        }
        for (JavaMethod method : KotlinBytecode.declaredMethods(type)) {
            if (!getter(type, method)) {
                continue;
            }
            String key = "method:" + method.getName();
            result = result.combine(constraints(method.getAnnotations()));
            result = result.combine(typeUseConstraints(annotations.getOrDefault(key, Set.of())));
            result = result.combine(cascade(
                    method.getReturnType(), method.isAnnotatedWith(Types.VALID), annotations, key, "", visited, depth));
        }
        for (JavaClass parent : type.getRawInterfaces()) {
            if (!JDK_PARENTS.contains(parent.getName())) {
                result = result.combine(inspect(parent, new HashSet<>(visited), depth + 1));
            }
        }
        if (type.getRawSuperclass().isPresent()
                && !JDK_PARENTS.contains(type.getRawSuperclass().get().getName())) {
            result = result.combine(inspect(type.getRawSuperclass().get(), new HashSet<>(visited), depth + 1));
        }
        return result;
    }

    private static boolean getter(JavaClass owner, JavaMethod method) {
        if (!method.getRawParameterTypes().isEmpty()
                || method.getModifiers().contains(JavaModifier.STATIC)
                || "void".equals(method.getRawReturnType().getName())) {
            return false;
        }
        String name = method.getName();
        return name.startsWith("get") && name.length() > 3
                || name.startsWith("is") && name.length() > 2
                || owner.isRecord()
                        && owner.getFields().stream()
                                .anyMatch(field -> field.getName().equals(name));
    }

    private static ValidationConstraints constraints(Set<? extends JavaAnnotation<?>> annotations) {
        ValidationConstraints result = ValidationConstraints.ABSENT;
        for (JavaAnnotation<?> annotation : annotations) {
            JavaClass type = annotation.getRawType();
            if (type.getName().startsWith(Types.CONSTRAINT_PACKAGE)
                    || type.isAnnotatedWith("jakarta.validation.Constraint")) {
                return ValidationConstraints.PRESENT;
            }
            if (!type.isFullyImported() && !Types.VALID.equals(type.getName())) {
                result = ValidationConstraints.UNKNOWN;
            }
        }
        return result;
    }

    private ValidationConstraints typeUseConstraints(Set<String> names) {
        ValidationConstraints result = ValidationConstraints.ABSENT;
        for (String name : names) {
            if (name.startsWith(Types.CONSTRAINT_PACKAGE)) {
                return ValidationConstraints.PRESENT;
            }
            JavaClass annotation = annotationTypes.get(name);
            if (annotation != null && annotation.isFullyImported()) {
                if (annotation.isAnnotatedWith("jakarta.validation.Constraint")) {
                    return ValidationConstraints.PRESENT;
                }
            } else if (!Types.VALID.equals(name) && !JSPECIFY_NULLABILITY.contains(name)) {
                result = ValidationConstraints.UNKNOWN;
            }
        }
        return result;
    }

    private ValidationConstraints cascade(
            JavaType type,
            boolean cascaded,
            Map<String, Set<String>> annotations,
            String key,
            String path,
            Set<String> visited,
            int depth) {
        cascaded |= annotations
                .getOrDefault(key + ":" + (path.isEmpty() ? "root" : path), Set.of())
                .contains(Types.VALID);
        JavaClass raw = type.toErasure();
        String name = raw.getName();
        if (raw.isArray()) {
            return cascade(raw.getComponentType(), cascaded, annotations, key, path + "[", visited, depth);
        }
        if (COLLECTIONS.contains(name) || MAPS.contains(name)) {
            if (!(type instanceof JavaParameterizedType parameterized)) {
                return cascaded ? ValidationConstraints.UNKNOWN : ValidationConstraints.ABSENT;
            }
            List<JavaType> arguments = parameterized.getActualTypeArguments();
            ValidationConstraints result = ValidationConstraints.ABSENT;
            for (int i = 0; i < arguments.size(); i++) {
                result = result.combine(cascade(
                        arguments.get(i),
                        cascaded && (!MAPS.contains(name) || i == 1),
                        annotations,
                        key,
                        path + i + ";",
                        visited,
                        depth));
            }
            return result;
        }
        if (!cascaded) {
            return ValidationConstraints.ABSENT;
        }
        if (raw.isPrimitive()
                || name.startsWith("java.lang.") && !"java.lang.Object".equals(name)
                || name.startsWith("java.time.")) {
            return ValidationConstraints.ABSENT;
        }
        return inspect(raw, new HashSet<>(visited), depth + 1);
    }
}
