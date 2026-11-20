package io.github.jdubois.bootui.engine.restapi;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import io.github.jdubois.bootui.engine.archunit.KotlinBytecode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Bounded Spring mapping metadata; never loads application classes or invokes annotation methods. */
final class SpringRequestMappings {
    private static final String ALIAS_FOR = "org.springframework.core.annotation.AliasFor";
    private static final int MAX_DEPTH = 10;

    private SpringRequestMappings() {}

    record Mapping(Map<String, Object> attributes) {
        Mapping {
            attributes = Map.copyOf(attributes);
        }

        List<String> strings(String key) {
            Object value = attributes.get(key);
            List<?> values =
                    value instanceof Object[] array ? Arrays.asList(array) : List.of(value == null ? "" : value);
            return values.stream()
                    .map(element -> element instanceof JavaEnumConstant constant ? constant.name() : element.toString())
                    .filter(text -> !text.isBlank())
                    .distinct()
                    .toList();
        }

        List<String> paths() {
            return strings("path").isEmpty() ? strings("value") : strings("path");
        }

        String version() {
            return strings("version").stream().findFirst().orElse("");
        }
    }

    record Handler(JavaMethod method, Mapping mapping, List<JavaMethod> declarations) {
        JavaMethod annotationMethod(String annotation) {
            return declarations.stream()
                    .filter(declaration -> declaration.isAnnotatedWith(annotation))
                    .findFirst()
                    .orElse(method);
        }
    }

    record Discovery(List<Handler> handlers, boolean incomplete) {}

    static Set<String> implementedInterfaces(JavaClass type) {
        Set<String> result = new LinkedHashSet<>();
        hierarchy(type).stream()
                .filter(JavaClass::isInterface)
                .map(JavaClass::getName)
                .forEach(result::add);
        return result;
    }

    static Optional<Mapping> typeMapping(JavaClass type) {
        for (JavaClass candidate : hierarchy(type)) {
            Optional<Mapping> mapping = mapping(candidate.getAnnotations());
            if (mapping.isPresent()) return mapping;
        }
        return Optional.empty();
    }

    static Discovery handlers(JavaClass type) {
        List<JavaClass> hierarchy = hierarchy(type);
        Map<String, JavaMethod> dispatch = new LinkedHashMap<>();
        // Classes win dispatch over interface declarations, independently of annotation search order.
        List<JavaClass> dispatchOrder = new ArrayList<>();
        hierarchy.stream().filter(candidate -> !candidate.isInterface()).forEach(dispatchOrder::add);
        hierarchy.stream().filter(JavaClass::isInterface).forEach(dispatchOrder::add);
        for (JavaClass candidate : dispatchOrder) {
            for (JavaMethod method : KotlinBytecode.declaredMethods(candidate)) {
                JavaMethod previous = dispatch.get(signature(method, type));
                if (previous == null
                        || previous.getOwner().isInterface()
                                && method.getOwner()
                                        .isAssignableTo(previous.getOwner().getName())) {
                    dispatch.put(signature(method, type), method);
                }
            }
        }
        boolean incomplete = false;
        Set<String> unresolved = new LinkedHashSet<>();
        for (JavaClass candidate : dispatchOrder) {
            for (JavaMethod bridge : candidate.getMethods()) {
                if (!bridge.getModifiers().contains(JavaModifier.BRIDGE)) continue;
                try {
                    JavaMethod target = bridgeTarget(bridge, hierarchy, new LinkedHashSet<>(), 0);
                    target = dispatch.getOrDefault(signature(target, type), target);
                    JavaMethod previous = dispatch.get(signature(bridge, type));
                    if (previous == null
                            || candidate.isAssignableTo(previous.getOwner().getName())) {
                        dispatch.put(signature(bridge, type), target);
                    }
                } catch (RuntimeException | LinkageError ex) {
                    unresolved.add(signature(bridge, type));
                    incomplete = true;
                }
            }
        }
        List<Handler> handlers = new ArrayList<>();
        for (JavaMethod method : new LinkedHashSet<>(dispatch.values())) {
            try {
                if (unresolved.contains(signature(method, type))) continue;
                List<JavaMethod> declarations = new ArrayList<>();
                // Spring starts merged method annotation lookup at the dispatched method's declaring type.
                for (JavaClass candidate : hierarchy(method.getOwner())) {
                    for (JavaMethod declaration : KotlinBytecode.declaredMethods(candidate)) {
                        if (!method.equals(dispatch.get(signature(declaration, type)))) continue;
                        Set<JavaModifier> modifiers = declaration.getModifiers();
                        if (!modifiers.contains(JavaModifier.PUBLIC)
                                && !modifiers.contains(JavaModifier.PROTECTED)
                                && !modifiers.contains(JavaModifier.PRIVATE)
                                && !declaration.getOwner().getPackageName().equals(type.getPackageName())) {
                            if (mapping(declaration.getAnnotations()).isPresent()) {
                                throw new IllegalStateException(
                                        "Cross-package Spring package-private dispatch is unresolved");
                            }
                            continue;
                        }
                        declarations.add(declaration);
                    }
                }
                for (JavaMethod declaration : declarations) {
                    Optional<Mapping> mapping = mapping(declaration.getAnnotations());
                    if (mapping.isPresent()) {
                        handlers.add(new Handler(method, mapping.get(), List.copyOf(declarations)));
                        break;
                    }
                }
            } catch (RuntimeException | LinkageError ex) {
                incomplete = true;
            }
        }
        return new Discovery(List.copyOf(handlers), incomplete);
    }

    private static JavaMethod bridgeTarget(
            JavaMethod bridge, List<JavaClass> hierarchy, Set<String> visited, int depth) {
        if (depth >= MAX_DEPTH || !visited.add(bridge.getFullName())) {
            throw new IllegalStateException("REST bridge exceeds analysis bound");
        }
        List<JavaMethod> targets = bridge.getMethodCallsFromSelf().stream()
                .filter(call -> call.getName().equals(bridge.getName()))
                .filter(call -> hierarchy.contains(call.getTargetOwner()))
                .flatMap(call -> call.getTarget().resolveMember().stream())
                .distinct()
                .toList();
        if (targets.size() != 1) throw new IllegalStateException("Unresolved Spring handler bridge");
        JavaMethod target = targets.get(0);
        if (target.getModifiers().contains(JavaModifier.BRIDGE)) {
            return bridgeTarget(target, hierarchy, visited, depth + 1);
        }
        if (KotlinBytecode.isCompilerGenerated(target)) {
            throw new IllegalStateException("Unresolved generated Spring handler target");
        }
        return target;
    }

    private static String signature(JavaMethod method, JavaClass controller) {
        Set<JavaModifier> modifiers = method.getModifiers();
        return (modifiers.contains(JavaModifier.PRIVATE)
                                || !modifiers.contains(JavaModifier.PUBLIC)
                                        && !modifiers.contains(JavaModifier.PROTECTED)
                                        && !method.getOwner().getPackageName().equals(controller.getPackageName())
                        ? method.getOwner().getName() + "#"
                        : "")
                + method.getName()
                + method.getRawParameterTypes().stream().map(JavaClass::getName).toList();
    }

    private static List<JavaClass> hierarchy(JavaClass type) {
        List<JavaClass> result = new ArrayList<>();
        hierarchy(type, result, new LinkedHashSet<>(), 0);
        return result;
    }

    private static void hierarchy(JavaClass type, List<JavaClass> result, Set<String> visited, int depth) {
        if ("java.lang.Object".equals(type.getName()) || !visited.add(type.getName())) return;
        if (depth >= MAX_DEPTH) throw new IllegalStateException("REST mapping hierarchy exceeds analysis bound");
        if (type.getSource().isEmpty()) throw new IllegalStateException("Unresolved Spring mapping ancestry");
        result.add(type);
        for (JavaType iface : type.getInterfaces()) {
            hierarchy(iface.toErasure(), result, visited, depth + 1);
        }
        type.getRawSuperclass().ifPresent(parent -> hierarchy(parent, result, visited, depth + 1));
    }

    private record Attribute(String annotation, String name) {}

    private static Optional<Mapping> mapping(Set<? extends JavaAnnotation<?>> annotations) {
        Mapping result = null;
        for (JavaAnnotation<?> annotation : annotations) {
            Optional<Mapping> candidate = mapping(annotation, Map.of(), new LinkedHashSet<>(), 0);
            if (candidate.isPresent()) {
                if (result != null) throw new IllegalStateException("Multiple Spring mapping annotations");
                result = candidate.get();
            }
        }
        return Optional.ofNullable(result);
    }

    private static Optional<Mapping> mapping(
            JavaAnnotation<?> annotation, Map<Attribute, Object> overrides, Set<String> visited, int depth) {
        String name = annotation.getRawType().getName();
        if (name.startsWith("java.lang.annotation.") || !visited.add(name)) return Optional.empty();
        if (depth >= MAX_DEPTH) throw new IllegalStateException("REST mapping annotation exceeds analysis bound");
        Map<String, Object> values = new LinkedHashMap<>(annotation.getProperties());
        Map<Attribute, Object> nestedOverrides = new LinkedHashMap<>(overrides);
        Map<Attribute, Object> aliases = new LinkedHashMap<>();
        Set<Attribute> nonDefaultAliases = new LinkedHashSet<>();
        for (JavaMethod attribute : annotation.getRawType().getMethods()) {
            if (!attribute.isAnnotatedWith(ALIAS_FOR)) continue;
            Attribute target =
                    canonicalAttribute(annotation.getRawType(), attribute.getName(), new LinkedHashSet<>(), 0);
            Object value = values.get(attribute.getName());
            if (value == null) continue;
            boolean nonDefault = !java.util.Objects.deepEquals(
                    value, attribute.getDefaultValue().orElse(null));
            if (nonDefault) {
                if (nonDefaultAliases.contains(target) && !java.util.Objects.deepEquals(aliases.get(target), value)) {
                    throw new IllegalStateException("Conflicting Spring mapping aliases");
                }
                nonDefaultAliases.add(target);
                aliases.put(target, value);
            } else {
                aliases.putIfAbsent(target, value);
            }
        }
        aliases.forEach(nestedOverrides::putIfAbsent);
        if (RestApiModel.Types.REQUEST_MAPPING.equals(name)) {
            nestedOverrides.forEach((attribute, value) -> {
                if (attribute.annotation().equals(name)) values.put(attribute.name(), value);
            });
            if (values.containsKey("path")) values.put("value", values.get("path"));
            return Optional.of(new Mapping(values));
        }
        Mapping result = null;
        for (JavaAnnotation<?> meta : annotation.getRawType().getAnnotations()) {
            Optional<Mapping> candidate = mapping(meta, nestedOverrides, new LinkedHashSet<>(visited), depth + 1);
            if (candidate.isPresent()) {
                if (result != null) throw new IllegalStateException("Multiple composed Spring mappings");
                result = candidate.get();
            }
        }
        return Optional.ofNullable(result);
    }

    private static Attribute canonicalAttribute(JavaClass type, String name, Set<Attribute> visited, int depth) {
        Attribute current = new Attribute(type.getName(), name);
        if (!visited.add(current)) {
            return visited.stream()
                    .filter(attribute -> attribute.annotation().equals(type.getName()))
                    .min(java.util.Comparator.comparing(Attribute::name))
                    .orElseThrow();
        }
        if (depth >= MAX_DEPTH) throw new IllegalStateException("REST mapping alias exceeds analysis bound");
        Optional<JavaAnnotation<JavaMethod>> alias = type.getMethod(name).tryGetAnnotationOfType(ALIAS_FOR);
        if (alias.isEmpty()) return current;
        String targetName = alias.get()
                .get("attribute")
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(text -> !text.isBlank())
                .orElseGet(() -> alias.get().get("value").map(Object::toString).orElse(""));
        if (targetName.isEmpty()) targetName = name;
        Object targetType = alias.get().get("annotation").orElse(null);
        JavaClass target = targetType instanceof JavaClass targetClass
                        && !"java.lang.annotation.Annotation".equals(targetClass.getName())
                ? targetClass
                : type;
        return canonicalAttribute(target, targetName, visited, depth + 1);
    }
}
