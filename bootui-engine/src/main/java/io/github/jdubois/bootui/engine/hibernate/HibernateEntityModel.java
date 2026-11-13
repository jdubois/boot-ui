package io.github.jdubois.bootui.engine.hibernate;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record HibernateEntityModel(String name, Class<?> javaType, List<HibernateAttributeModel> attributes) {

    static final int MAX_EMBEDDED_DEPTH = 5;

    private static final String ATTRIBUTE_OVERRIDE = "jakarta.persistence.AttributeOverride";
    private static final String ATTRIBUTE_OVERRIDES = "jakarta.persistence.AttributeOverrides";
    private static final String BATCH_SIZE = "org.hibernate.annotations.BatchSize";
    private static final String CACHE = "org.hibernate.annotations.Cache";
    private static final String CACHEABLE = "jakarta.persistence.Cacheable";
    private static final String CONVERT = "jakarta.persistence.Convert";
    private static final String CONVERTS = "jakarta.persistence.Converts";
    private static final String EMBEDDABLE = "jakarta.persistence.Embeddable";
    private static final String EMBEDDED = "jakarta.persistence.Embedded";
    private static final String EMBEDDED_ID = "jakarta.persistence.EmbeddedId";
    private static final String PERSISTENT_ATTRIBUTE_INTERCEPTABLE =
            "org.hibernate.engine.spi.PersistentAttributeInterceptable";

    record ScopedOverride(String relativePath, Annotation annotation) {}

    public HibernateEntityModel {
        attributes = List.copyOf(attributes);
    }

    static HibernateEntityModel fromClass(Class<?> javaType) {
        List<HibernateAttributeModel> attributes = new ArrayList<>();
        Set<Class<?>> visited = new HashSet<>();
        if (javaType != null) {
            visited.add(javaType);
        }
        collectClassAttributes(javaType, "", classLevelOverrides(javaType), visited, 0, attributes);
        return new HibernateEntityModel(javaType.getName(), javaType, attributes);
    }

    static void collectClassAttributes(
            Class<?> type,
            String prefix,
            List<ScopedOverride> inheritedOverrides,
            Set<Class<?>> visited,
            int depth,
            List<HibernateAttributeModel> attributes) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    String simpleName = field.getName();
                    String qualifiedName = prefix.isEmpty() ? simpleName : prefix + "." + simpleName;
                    HibernateAttributeModel attr = HibernateAttributeModel.from(
                            field, qualifiedName, matchingOverrides(inheritedOverrides, simpleName));
                    attributes.add(attr);
                    expandEmbeddedIfApplicable(
                            attr, simpleName, qualifiedName, inheritedOverrides, visited, depth, attributes);
                }
            }
            for (Method method : current.getDeclaredMethods()) {
                if (method.getParameterCount() == 0
                        && method.getReturnType() != Void.TYPE
                        && !method.isSynthetic()
                        && !method.isBridge()
                        && !Modifier.isStatic(method.getModifiers())
                        && Arrays.stream(method.getAnnotations()).anyMatch(HibernateAttributeModel::isJpaAnnotation)) {
                    String methodDisplayName = method.getName() + "()";
                    String qualifiedName = prefix.isEmpty() ? methodDisplayName : prefix + "." + methodDisplayName;
                    String simpleProperty = HibernateAttributeModel.propertyNameFromMethodName(method.getName());
                    HibernateAttributeModel attr = HibernateAttributeModel.from(
                            method, qualifiedName, matchingOverrides(inheritedOverrides, simpleProperty));
                    attributes.add(attr);
                    String childPrefix = prefix.isEmpty() ? simpleProperty : prefix + "." + simpleProperty;
                    expandEmbeddedIfApplicable(
                            attr, simpleProperty, childPrefix, inheritedOverrides, visited, depth, attributes);
                }
            }
            current = current.getSuperclass();
        }
    }

    private static void expandEmbeddedIfApplicable(
            HibernateAttributeModel attr,
            String simpleName,
            String qualifiedName,
            List<ScopedOverride> inheritedOverrides,
            Set<Class<?>> visited,
            int depth,
            List<HibernateAttributeModel> attributes) {
        if (depth >= MAX_EMBEDDED_DEPTH || !isExpandableEmbedded(attr)) {
            return;
        }
        Class<?> embeddableType = attr.rawType();
        if (embeddableType != null && visited.add(embeddableType)) {
            try {
                List<ScopedOverride> nextOverrides =
                        childOverrides(inheritedOverrides, attr.annotations(), simpleName, embeddableType);
                collectClassAttributes(embeddableType, qualifiedName, nextOverrides, visited, depth + 1, attributes);
            } finally {
                visited.remove(embeddableType);
            }
        }
    }

    static boolean isExpandableEmbedded(HibernateAttributeModel attr) {
        if (attr == null
                || attr.rawType() == null
                || attr.isTransient()
                || attr.isAssociation()
                || attr.isElementCollection()
                || attr.hasId()
                || attr.annotation(EMBEDDED_ID) != null) {
            return false;
        }
        return "EMBEDDED".equals(attr.persistentAttributeType())
                || attr.annotation(EMBEDDED) != null
                || hasAnnotation(attr.rawType(), EMBEDDABLE);
    }

    static List<ScopedOverride> classLevelOverrides(Class<?> javaType) {
        List<ScopedOverride> overrides = new ArrayList<>();
        Class<?> current = javaType;
        while (current != null && current != Object.class) {
            extractScopedOverrides(Arrays.asList(current.getDeclaredAnnotations()), overrides);
            current = current.getSuperclass();
        }
        return List.copyOf(overrides);
    }

    static List<ScopedOverride> childOverrides(
            List<ScopedOverride> inheritedOverrides,
            List<Annotation> memberAnnotations,
            String segment,
            Class<?> embeddableType) {
        List<ScopedOverride> result = new ArrayList<>();
        String dotPrefix = segment + ".";
        for (ScopedOverride override : inheritedOverrides) {
            if (override.relativePath().startsWith(dotPrefix)) {
                result.add(new ScopedOverride(
                        override.relativePath().substring(dotPrefix.length()), override.annotation()));
            }
        }
        extractScopedOverrides(memberAnnotations, result);
        if (embeddableType != null) {
            result.addAll(classLevelOverrides(embeddableType));
        }
        return List.copyOf(result);
    }

    static List<Annotation> matchingOverrides(List<ScopedOverride> overrides, String simpleName) {
        if (overrides.isEmpty() || simpleName == null) {
            return List.of();
        }
        List<Annotation> matched = new ArrayList<>();
        for (ScopedOverride override : overrides) {
            if (simpleName.equals(override.relativePath())
                    || override.relativePath().isEmpty()) {
                matched.add(override.annotation());
            }
        }
        return matched;
    }

    private static void extractScopedOverrides(List<Annotation> annotations, List<ScopedOverride> target) {
        for (Annotation annotation : annotations) {
            String typeName = annotation.annotationType().getName();
            if (ATTRIBUTE_OVERRIDE.equals(typeName)) {
                addAttributeOverride(annotation, target);
            } else if (ATTRIBUTE_OVERRIDES.equals(typeName)) {
                Object value = annotationValue(annotation, "value");
                if (value instanceof Annotation[] array) {
                    for (Annotation item : array) {
                        addAttributeOverride(item, target);
                    }
                }
            } else if (CONVERT.equals(typeName)) {
                addConvertOverride(annotation, target);
            } else if (CONVERTS.equals(typeName)) {
                Object value = annotationValue(annotation, "value");
                if (value instanceof Annotation[] array) {
                    for (Annotation item : array) {
                        addConvertOverride(item, target);
                    }
                }
            }
        }
    }

    private static void addAttributeOverride(Annotation override, List<ScopedOverride> target) {
        Object name = annotationValue(override, "name");
        Object column = annotationValue(override, "column");
        if (name instanceof String path && !path.isBlank() && column instanceof Annotation columnAnnotation) {
            target.add(new ScopedOverride(path.trim(), columnAnnotation));
        }
    }

    private static void addConvertOverride(Annotation convert, List<ScopedOverride> target) {
        Object attributeName = annotationValue(convert, "attributeName");
        String path = attributeName instanceof String text ? text.trim() : "";
        target.add(new ScopedOverride(path, convert));
    }

    String packageName() {
        if (javaType == null || javaType.getPackageName().isBlank()) {
            return "";
        }
        return javaType.getPackageName();
    }

    boolean hasBatchSizeAnnotation() {
        return javaType != null && hasAnnotation(javaType, BATCH_SIZE);
    }

    boolean hasVersionAttribute() {
        return attributes.stream().anyMatch(HibernateAttributeModel::hasVersion);
    }

    boolean isFinalClass() {
        return javaType != null && Modifier.isFinal(javaType.getModifiers());
    }

    boolean hasDynamicUpdate() {
        return annotationInHierarchy("org.hibernate.annotations.DynamicUpdate") != null;
    }

    boolean isImmutable() {
        return annotationInHierarchy("org.hibernate.annotations.Immutable") != null;
    }

    boolean isBytecodeEnhanced() {
        return javaType != null && implementsInterface(javaType, PERSISTENT_ATTRIBUTE_INTERCEPTABLE);
    }

    Annotation hibernateCacheAnnotation() {
        return annotationInHierarchy("org.hibernate.annotations.Cache");
    }

    String hibernateCacheUsageName() {
        return annotationValueName(hibernateCacheAnnotation(), "usage");
    }

    boolean hasHibernateCacheAnnotation() {
        return annotation(CACHE) != null;
    }

    boolean isJpaCacheable() {
        Annotation cacheable = annotation(CACHEABLE);
        if (cacheable == null) {
            return false;
        }
        Boolean value = annotationBooleanValue(cacheable, "value");
        return value == null || value;
    }

    Annotation annotation(String typeName) {
        return javaType == null ? null : annotation(javaType, typeName);
    }

    Annotation annotationInHierarchy(String typeName) {
        Class<?> current = javaType;
        while (current != null && current != Object.class) {
            Annotation annotation = annotation(current, typeName);
            if (annotation != null) {
                return annotation;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    String annotationValueName(Annotation annotation, String attributeName) {
        Object value = annotationValue(annotation, attributeName);
        return value instanceof Enum<?> enumValue ? enumValue.name() : null;
    }

    String annotationStringValue(Annotation annotation, String attributeName) {
        Object value = annotationValue(annotation, attributeName);
        return value instanceof String stringValue ? stringValue : null;
    }

    Integer annotationIntValue(Annotation annotation, String attributeName) {
        Object value = annotationValue(annotation, attributeName);
        return value instanceof Integer integerValue ? integerValue : null;
    }

    Boolean annotationBooleanValue(Annotation annotation, String attributeName) {
        Object value = annotationValue(annotation, attributeName);
        return value instanceof Boolean booleanValue ? booleanValue : null;
    }

    List<HibernateAttributeModel> collectionAttributes() {
        return attributes.stream()
                .filter(HibernateAttributeModel::isCollectionAssociation)
                .toList();
    }

    boolean overridesEquals() {
        return declaresMethod("equals", Object.class);
    }

    boolean overridesHashCode() {
        return declaresMethod("hashCode");
    }

    private boolean declaresMethod(String name, Class<?>... parameterTypes) {
        if (javaType == null) {
            return false;
        }

        try {
            Method method = javaType.getMethod(name, parameterTypes);
            return method.getDeclaringClass() != Object.class;
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }

    private static boolean implementsInterface(Class<?> type, String interfaceName) {
        Class<?> current = type;
        while (current != null) {
            for (Class<?> candidate : current.getInterfaces()) {
                if (interfaceName.equals(candidate.getName()) || implementsInterface(candidate, interfaceName)) {
                    return true;
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private static boolean hasAnnotation(AnnotatedElement element, String typeName) {
        return Arrays.stream(element.getAnnotations())
                .map(annotation -> annotation.annotationType().getName())
                .anyMatch(typeName::equals);
    }

    private static Annotation annotation(AnnotatedElement element, String typeName) {
        return Arrays.stream(element.getAnnotations())
                .filter(candidate -> candidate.annotationType().getName().equals(typeName))
                .findFirst()
                .orElse(null);
    }

    private static Object annotationValue(Annotation annotation, String attributeName) {
        if (annotation == null) {
            return null;
        }
        try {
            Method method = annotation.annotationType().getMethod(attributeName);
            return method.invoke(annotation);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }
}
