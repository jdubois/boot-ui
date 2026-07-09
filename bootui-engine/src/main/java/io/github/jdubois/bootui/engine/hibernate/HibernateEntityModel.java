package io.github.jdubois.bootui.engine.hibernate;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public record HibernateEntityModel(String name, Class<?> javaType, List<HibernateAttributeModel> attributes) {

    private static final String BATCH_SIZE = "org.hibernate.annotations.BatchSize";
    private static final String CACHE = "org.hibernate.annotations.Cache";
    private static final String CACHEABLE = "jakarta.persistence.Cacheable";

    public HibernateEntityModel {
        attributes = List.copyOf(attributes);
    }

    static HibernateEntityModel fromClass(Class<?> javaType) {
        List<HibernateAttributeModel> attributes = new ArrayList<>();
        Class<?> current = javaType;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    attributes.add(HibernateAttributeModel.from(field));
                }
            }
            for (Method method : current.getDeclaredMethods()) {
                if (method.getParameterCount() == 0
                        && method.getReturnType() != Void.TYPE
                        && !method.isSynthetic()
                        && !method.isBridge()
                        && !Modifier.isStatic(method.getModifiers())
                        && Arrays.stream(method.getAnnotations()).anyMatch(HibernateAttributeModel::isJpaAnnotation)) {
                    attributes.add(HibernateAttributeModel.from(method));
                }
            }
            current = current.getSuperclass();
        }
        return new HibernateEntityModel(javaType.getName(), javaType, attributes);
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

    String inheritanceStrategy() {
        Annotation inheritance = annotationInHierarchy("jakarta.persistence.Inheritance");
        return annotationValueName(inheritance, "strategy");
    }

    boolean hasDiscriminatorColumn() {
        return annotationInHierarchy("jakarta.persistence.DiscriminatorColumn") != null;
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

    boolean overridesToString() {
        return declaresMethod("toString");
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
