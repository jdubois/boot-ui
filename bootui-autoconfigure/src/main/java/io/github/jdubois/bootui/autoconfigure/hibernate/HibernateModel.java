package io.github.jdubois.bootui.autoconfigure.hibernate;

import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

record HibernateEntityModel(String name, Class<?> javaType, List<HibernateAttributeModel> attributes) {

    private static final String BATCH_SIZE = "org.hibernate.annotations.BatchSize";
    private static final String CACHE = "org.hibernate.annotations.Cache";
    private static final String CACHEABLE = "jakarta.persistence.Cacheable";
    private static final String VERSION = "jakarta.persistence.Version";

    HibernateEntityModel {
        attributes = List.copyOf(attributes);
    }

    static HibernateEntityModel from(EntityType<?> entityType) {
        Class<?> javaType = entityType.getJavaType();
        List<HibernateAttributeModel> attributes = new ArrayList<>();
        for (Attribute<?, ?> attribute : entityType.getAttributes()) {
            Member member = attribute.getJavaMember();
            if (member != null) {
                attributes.add(HibernateAttributeModel.from(attribute, member));
            }
        }
        String name = javaType == null ? entityType.getName() : javaType.getName();
        return new HibernateEntityModel(name, javaType, attributes);
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

record HibernateAttributeModel(
        String entityName,
        String name,
        Class<?> rawType,
        Type genericType,
        String persistentAttributeType,
        boolean publicMember,
        List<Annotation> annotations) {

    private static final String BASIC = "jakarta.persistence.Basic";
    private static final String BATCH_SIZE = "org.hibernate.annotations.BatchSize";
    private static final String CACHE = "org.hibernate.annotations.Cache";
    private static final String COLUMN = "jakarta.persistence.Column";
    private static final String CONVERT = "jakarta.persistence.Convert";
    private static final String ELEMENT_COLLECTION = "jakarta.persistence.ElementCollection";
    private static final String ENUMERATED = "jakarta.persistence.Enumerated";
    private static final String FETCH = "org.hibernate.annotations.Fetch";
    private static final String GENERATED_VALUE = "jakarta.persistence.GeneratedValue";
    private static final String ID = "jakarta.persistence.Id";
    private static final String JOIN_COLUMN = "jakarta.persistence.JoinColumn";
    private static final String JOIN_COLUMNS = "jakarta.persistence.JoinColumns";
    private static final String LOB = "jakarta.persistence.Lob";
    private static final String MANY_TO_MANY = "jakarta.persistence.ManyToMany";
    private static final String MANY_TO_ONE = "jakarta.persistence.ManyToOne";
    private static final String MAPS_ID = "jakarta.persistence.MapsId";
    private static final String ONE_TO_MANY = "jakarta.persistence.OneToMany";
    private static final String ONE_TO_ONE = "jakarta.persistence.OneToOne";
    private static final String ORDER_BY = "jakarta.persistence.OrderBy";
    private static final String ORDER_COLUMN = "jakarta.persistence.OrderColumn";
    private static final String SEQUENCE_GENERATOR = "jakarta.persistence.SequenceGenerator";
    private static final String TRANSIENT = "jakarta.persistence.Transient";
    private static final String UUID_GENERATOR = "org.hibernate.annotations.UuidGenerator";
    private static final String VERSION = "jakarta.persistence.Version";

    HibernateAttributeModel {
        annotations = List.copyOf(annotations);
    }

    static HibernateAttributeModel from(Attribute<?, ?> attribute, Member member) {
        RawType rawType = rawType(member);
        String entityName = member.getDeclaringClass().getName();
        String persistentAttributeType = attribute.getPersistentAttributeType() == null
                ? null
                : attribute.getPersistentAttributeType().name();
        return new HibernateAttributeModel(
                entityName,
                attribute.getName(),
                rawType.rawType(),
                rawType.genericType(),
                persistentAttributeType,
                Modifier.isPublic(member.getModifiers()),
                annotations(member));
    }

    static HibernateAttributeModel from(Field field) {
        return new HibernateAttributeModel(
                field.getDeclaringClass().getName(),
                field.getName(),
                field.getType(),
                field.getGenericType(),
                persistentAttributeType(field),
                Modifier.isPublic(field.getModifiers()),
                List.of(field.getAnnotations()));
    }

    static HibernateAttributeModel from(Method method) {
        return new HibernateAttributeModel(
                method.getDeclaringClass().getName(),
                method.getName() + "()",
                method.getReturnType(),
                method.getGenericReturnType(),
                persistentAttributeType(method),
                Modifier.isPublic(method.getModifiers()),
                List.of(method.getAnnotations()));
    }

    boolean isAssociation() {
        return isAssociationType(persistentAttributeType)
                || annotation(MANY_TO_ONE) != null
                || annotation(ONE_TO_ONE) != null
                || annotation(ONE_TO_MANY) != null
                || annotation(MANY_TO_MANY) != null;
    }

    boolean isToOneAssociation() {
        return "MANY_TO_ONE".equals(persistentAttributeType)
                || "ONE_TO_ONE".equals(persistentAttributeType)
                || annotation(MANY_TO_ONE) != null
                || annotation(ONE_TO_ONE) != null;
    }

    boolean isOneToMany() {
        return "ONE_TO_MANY".equals(persistentAttributeType) || annotation(ONE_TO_MANY) != null;
    }

    boolean isManyToMany() {
        return "MANY_TO_MANY".equals(persistentAttributeType) || annotation(MANY_TO_MANY) != null;
    }

    boolean isCollectionAssociation() {
        return isOneToMany() || isManyToMany();
    }

    boolean isEnumAttribute() {
        return rawType != null && rawType.isEnum() && annotation(TRANSIENT) == null;
    }

    boolean isListAttribute() {
        return rawType != null && java.util.List.class.isAssignableFrom(rawType);
    }

    boolean isBagAttribute() {
        return isCollectionAssociation()
                && rawType != null
                && Collection.class.isAssignableFrom(rawType)
                && !Set.class.isAssignableFrom(rawType)
                && !hasOrderColumn();
    }

    boolean isOptionalAttribute() {
        return rawType != null && Optional.class.equals(rawType) && annotation(TRANSIENT) == null;
    }

    boolean hasBatchSizeAnnotation() {
        return annotation(BATCH_SIZE) != null;
    }

    boolean hasGeneratedValue() {
        return annotation(GENERATED_VALUE) != null;
    }

    boolean hasId() {
        return annotation(ID) != null;
    }

    boolean hasMapsId() {
        return annotation(MAPS_ID) != null;
    }

    boolean hasOrderColumn() {
        return annotation(ORDER_COLUMN) != null;
    }

    boolean hasVersion() {
        return annotation(VERSION) != null;
    }

    boolean hasJoinColumn() {
        return annotation(JOIN_COLUMN) != null || annotation(JOIN_COLUMNS) != null;
    }

    boolean hasConvertAnnotation() {
        return annotation(CONVERT) != null;
    }

    boolean isLob() {
        return annotation(LOB) != null;
    }

    boolean isElementCollection() {
        return annotation(ELEMENT_COLLECTION) != null;
    }

    boolean hasBasicLazy() {
        Annotation basic = annotation(BASIC);
        return basic != null && "LAZY".equals(annotationValueName(basic, "fetch"));
    }

    boolean hasOrderBy() {
        return annotation(ORDER_BY) != null;
    }

    boolean isUuidType() {
        return rawType != null && java.util.UUID.class.equals(rawType) && annotation(TRANSIENT) == null;
    }

    boolean hasUuidGenerator() {
        return annotation(UUID_GENERATOR) != null;
    }

    boolean isStringType() {
        return rawType != null && String.class.equals(rawType) && annotation(TRANSIENT) == null;
    }

    boolean isBigDecimalType() {
        return rawType != null && java.math.BigDecimal.class.equals(rawType) && annotation(TRANSIENT) == null;
    }

    boolean isLegacyTemporalType() {
        if (rawType == null || annotation(TRANSIENT) != null) {
            return false;
        }
        return rawType.equals(java.util.Date.class)
                || rawType.equals(java.util.Calendar.class)
                || rawType.equals(java.sql.Date.class)
                || rawType.equals(java.sql.Time.class)
                || rawType.equals(java.sql.Timestamp.class);
    }

    boolean hasHibernateCacheAnnotation() {
        return annotation(CACHE) != null;
    }

    Annotation columnAnnotation() {
        return annotation(COLUMN);
    }

    Annotation elementCollectionAnnotation() {
        return annotation(ELEMENT_COLLECTION);
    }

    Annotation fetchAnnotation() {
        return annotation(FETCH);
    }

    Annotation joinColumnAnnotation() {
        return annotation(JOIN_COLUMN);
    }

    Annotation associationAnnotation() {
        for (String typeName : List.of(MANY_TO_ONE, ONE_TO_ONE, ONE_TO_MANY, MANY_TO_MANY)) {
            Annotation annotation = annotation(typeName);
            if (annotation != null) {
                return annotation;
            }
        }
        return null;
    }

    Annotation enumeratedAnnotation() {
        return annotation(ENUMERATED);
    }

    Annotation generatedValueAnnotation() {
        return annotation(GENERATED_VALUE);
    }

    Annotation manyToManyAnnotation() {
        return annotation(MANY_TO_MANY);
    }

    Annotation manyToOneAnnotation() {
        return annotation(MANY_TO_ONE);
    }

    Annotation oneToManyAnnotation() {
        return annotation(ONE_TO_MANY);
    }

    Annotation oneToOneAnnotation() {
        return annotation(ONE_TO_ONE);
    }

    Annotation sequenceGeneratorAnnotation() {
        return annotation(SEQUENCE_GENERATOR);
    }

    String description() {
        return entityName + "#" + name;
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

    boolean annotationEnumArrayContains(Annotation annotation, String attributeName, String expectedName) {
        Object value = annotationValue(annotation, attributeName);
        if (!(value instanceof Object[] values)) {
            return false;
        }
        for (Object item : values) {
            if (item instanceof Enum<?> enumValue && expectedName.equals(enumValue.name())) {
                return true;
            }
        }
        return false;
    }

    Annotation annotation(String typeName) {
        for (Annotation annotation : annotations) {
            if (annotation.annotationType().getName().equals(typeName)) {
                return annotation;
            }
        }
        return null;
    }

    static boolean isJpaAnnotation(Annotation annotation) {
        return annotation.annotationType().getName().startsWith("jakarta.persistence.");
    }

    private static boolean isAssociationType(String type) {
        return "MANY_TO_ONE".equals(type)
                || "ONE_TO_ONE".equals(type)
                || "ONE_TO_MANY".equals(type)
                || "MANY_TO_MANY".equals(type);
    }

    private static RawType rawType(Member member) {
        if (member instanceof Field field) {
            return new RawType(field.getType(), field.getGenericType());
        }
        if (member instanceof Method method) {
            return new RawType(method.getReturnType(), method.getGenericReturnType());
        }
        return new RawType(Object.class, Object.class);
    }

    private static List<Annotation> annotations(Member member) {
        if (member instanceof AnnotatedElement element) {
            return List.of(element.getAnnotations());
        }
        return List.of();
    }

    private static String persistentAttributeType(AnnotatedElement element) {
        if (hasAnnotation(element, MANY_TO_ONE)) {
            return "MANY_TO_ONE";
        }
        if (hasAnnotation(element, ONE_TO_ONE)) {
            return "ONE_TO_ONE";
        }
        if (hasAnnotation(element, ONE_TO_MANY)) {
            return "ONE_TO_MANY";
        }
        if (hasAnnotation(element, MANY_TO_MANY)) {
            return "MANY_TO_MANY";
        }
        return null;
    }

    private static boolean hasAnnotation(AnnotatedElement element, String typeName) {
        return Arrays.stream(element.getAnnotations())
                .map(annotation -> annotation.annotationType().getName())
                .anyMatch(typeName::equals);
    }

    private Object annotationValue(Annotation annotation, String attributeName) {
        try {
            Method method = annotation.annotationType().getMethod(attributeName);
            return method.invoke(annotation);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    private record RawType(Class<?> rawType, Type genericType) {}
}

record HibernateRepositoryModel(
        String repositoryInterface, Class<?> domainType, List<HibernateRepositoryMethodModel> methods) {

    HibernateRepositoryModel {
        methods = List.copyOf(methods);
    }
}

record HibernateRepositoryMethodModel(
        String repositoryInterface,
        String methodName,
        Class<?> domainType,
        Class<?> returnType,
        String query,
        boolean nativeQuery,
        String countQuery,
        boolean hasPageableParameter,
        boolean modifying,
        boolean modifyingClearsAutomatically,
        boolean modifyingFlushesAutomatically,
        List<Class<?>> parameterTypes) {

    HibernateRepositoryMethodModel {
        parameterTypes = List.copyOf(parameterTypes);
    }

    boolean hasCollectionParameter() {
        for (Class<?> parameterType : parameterTypes) {
            if (parameterType.isArray() || Collection.class.isAssignableFrom(parameterType)) {
                return true;
            }
        }
        return false;
    }

    boolean hasQuery() {
        return query != null && !query.isBlank();
    }

    boolean hasCountQuery() {
        return countQuery != null && !countQuery.isBlank();
    }

    boolean returnsStream() {
        return returnType != null && java.util.stream.Stream.class.isAssignableFrom(returnType);
    }

    // Optional Spring Data type: compare by class name instead of hard-referencing a class that may be absent at
    // runtime.
    @SuppressWarnings("java:S1872")
    boolean returnsPage() {
        return returnType != null && "org.springframework.data.domain.Page".equals(returnType.getName());
    }

    // Optional Spring Data type: compare by class name instead of hard-referencing a class that may be absent at
    // runtime.
    @SuppressWarnings("java:S1872")
    boolean returnsSlice() {
        return returnType != null && "org.springframework.data.domain.Slice".equals(returnType.getName());
    }

    boolean returnsMultiple() {
        if (returnType == null) {
            return false;
        }
        return returnsStream()
                || returnsPage()
                || returnsSlice()
                || returnType.isArray()
                || Collection.class.isAssignableFrom(returnType);
    }

    boolean isDerivedDeleteMethod() {
        return methodName != null && (methodName.startsWith("deleteBy") || methodName.startsWith("removeBy"));
    }

    String description() {
        return repositoryInterface + "#" + methodName;
    }
}
