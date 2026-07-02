package io.github.jdubois.bootui.engine.hibernate;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public record HibernateAttributeModel(
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

    public HibernateAttributeModel {
        annotations = List.copyOf(annotations);
    }

    static HibernateAttributeModel fromMember(String name, Member member, String persistentAttributeType) {
        RawType rawType = rawType(member);
        String entityName = member.getDeclaringClass().getName();
        return new HibernateAttributeModel(
                entityName,
                name,
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

    boolean isTransient() {
        return annotation(TRANSIENT) != null;
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
