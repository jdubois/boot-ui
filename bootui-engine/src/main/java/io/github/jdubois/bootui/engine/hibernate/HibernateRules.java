package io.github.jdubois.bootui.engine.hibernate;

import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import io.github.jdubois.bootui.engine.support.KotlinReflection;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

abstract class AbstractHibernateRule implements HibernateRule {

    private final HibernateRuleDefinition definition;

    AbstractHibernateRule(HibernateRuleDefinition definition) {
        this.definition = definition;
    }

    @Override
    public final HibernateRuleDefinition definition() {
        return definition;
    }

    abstract HibernateRuleResultDto evaluateRule(HibernateContext context);

    @Override
    public final HibernateRuleResultDto evaluate(HibernateContext context) {
        context.evidence().reset();
        try {
            HibernateRuleResultDto result = evaluateRule(context);
            context.evidence().complete(result);
            return context.evidence().requiredUnknown() && HibernateRuleSupport.PASS.equals(result.status())
                    ? skipped("Required observation is unavailable.")
                    : result;
        } catch (HibernateRequiredObservationException ex) {
            return skipped("Required observation is unavailable.");
        } catch (RuntimeException | LinkageError ex) {
            return HibernateRuleSupport.error(definition, "Rule evaluation failed.");
        }
    }

    HibernateRuleResultDto pass() {
        return HibernateRuleSupport.pass(definition);
    }

    HibernateRuleResultDto skipped(String reason) {
        return HibernateRuleSupport.skipped(definition, reason);
    }

    HibernateRuleResultDto violation(List<String> details) {
        return details.isEmpty() ? pass() : HibernateRuleSupport.violation(definition, details);
    }

    HibernateRuleResultDto violation(String severityOverride, List<String> details) {
        return details.isEmpty() ? pass() : HibernateRuleSupport.violation(definition, severityOverride, details);
    }

    HibernateRuleResultDto violation(String severityOverride, String detail) {
        return HibernateRuleSupport.violation(definition, severityOverride, List.of(detail));
    }
}

final class HibernateRuleModelSupport {

    private static final Pattern FROM_ALIAS =
            Pattern.compile("\\bfrom\\s+[\\w.$]+\\s+(?:as\\s+)?([A-Za-z_]\\w*)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern JOIN_FETCH = Pattern.compile(
            "\\bjoin\\s++fetch\\s++([A-Za-z_]\\w*+(?:\\.[A-Za-z_]\\w*+)++)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_CLAUSE =
            Pattern.compile("^\\s*select\\b(.*?)\\bfrom\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DISTINCT_PREFIX = Pattern.compile("(?i)^distinct\\s+");
    private static final Pattern OBJECT_WRAPPER = Pattern.compile("(?i)^object\\(\\s*([A-Za-z_]\\w*)\\s*\\)$");

    // Panache base-class names are compared by string so bootui-engine never imports io.quarkus.* directly
    // (EngineBoundaryArchitectureTests bans it); presence is probed reflectively instead.
    private static final String PANACHE_ORM_ENTITY_BASE = "io.quarkus.hibernate.orm.panache.PanacheEntityBase";
    private static final String PANACHE_REACTIVE_ENTITY_BASE =
            "io.quarkus.hibernate.reactive.panache.PanacheEntityBase";
    private static final String PANACHE_ORM_ENTITY = "io.quarkus.hibernate.orm.panache.PanacheEntity";
    private static final String PANACHE_REACTIVE_ENTITY = "io.quarkus.hibernate.reactive.panache.PanacheEntity";
    private static final String SPRING_DATA_PERSISTABLE = "org.springframework.data.domain.Persistable";

    private HibernateRuleModelSupport() {}

    /**
     * True when a Quarkus Panache extension (ORM or Reactive) is present on the classpath. Panache rewrites public
     * field access application-wide on every JPA-managed class, not only on classes that extend
     * {@code PanacheEntityBase}/{@code PanacheEntity} (see {@code PanacheHibernateCommonResourceProcessor} and
     * {@code MetamodelInfo} in the Quarkus Panache deployment sources), so once either extension is present, a
     * public persistent field is safe from bypassing Hibernate's instrumentation and no longer a real risk.
     */
    static boolean isPanacheFieldAccessRewriteActive() {
        return isClassPresent(PANACHE_ORM_ENTITY_BASE) || isClassPresent(PANACHE_REACTIVE_ENTITY_BASE);
    }

    /**
     * True when the attribute is the {@code id} field declared by Panache's own {@code PanacheEntity} (ORM or
     * Reactive) base class ({@code @Id @GeneratedValue public Long id;}), which the application inherits as-is and
     * cannot annotate or edit.
     */
    static boolean isFrameworkDeclaredPanacheIdentifier(HibernateAttributeModel attribute) {
        return PANACHE_ORM_ENTITY.equals(attribute.entityName())
                || PANACHE_REACTIVE_ENTITY.equals(attribute.entityName());
    }

    /** True when Spring Data Commons' {@code Persistable} marker interface is present on the classpath. */
    static boolean isSpringDataPersistableAvailable() {
        return isClassPresent(SPRING_DATA_PERSISTABLE);
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, HibernateRuleModelSupport.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ex) {
            return false;
        }
    }

    static Map<String, HibernateEntityModel> entitiesByJavaType(List<HibernateEntityModel> entities) {
        Map<String, HibernateEntityModel> byJavaType = new LinkedHashMap<>();
        for (HibernateEntityModel entity : entities) {
            if (entity.javaType() != null) {
                byJavaType.put(entity.javaType().getName(), entity);
            }
        }
        return byJavaType;
    }

    static HibernateEntityModel entityForDomainType(HibernateContext context, Class<?> domainType) {
        if (domainType == null) {
            return null;
        }
        for (HibernateEntityModel entity : context.entities()) {
            if (domainType.equals(entity.javaType())) {
                return entity;
            }
        }
        return null;
    }

    static String rootAlias(String query) {
        query = HibernateQueryShape.lexical(query);
        if (query == null) {
            return null;
        }
        Matcher matcher = FROM_ALIAS.matcher(query);
        return matcher.find() ? matcher.group(1) : null;
    }

    static List<String> joinFetchPaths(String query) {
        List<String> paths = new ArrayList<>();
        query = HibernateQueryShape.lexical(query);
        if (query == null) {
            return paths;
        }
        Matcher matcher = JOIN_FETCH.matcher(query);
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
        return paths;
    }

    static String directAttribute(String rootAlias, String path) {
        if (rootAlias == null || path == null) {
            return null;
        }
        String prefix = rootAlias + ".";
        if (!path.startsWith(prefix)) {
            return null;
        }
        String remainder = path.substring(prefix.length());
        return remainder.indexOf('.') == -1 ? remainder : null;
    }

    /**
     * Returns one detail per Spring Data repository method that pages (Pageable parameter) a JPQL query whose
     * {@code JOIN FETCH} targets a collection association declared directly on the query root. Shared by HIB-FETCH-003
     * (which reports these as violations) and HIB-CONFIG-016 (which uses their presence to pick a dynamic severity).
     */
    static List<String> paginatedCollectionFetchFindings(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateRepositoryModel repository : context.repositories()) {
            for (HibernateRepositoryMethodModel method : repository.methods()) {
                if (!method.hasPageableParameter() || method.nativeQuery() || method.query() == null) {
                    continue;
                }
                HibernateEntityModel domainEntity = HibernateQueryShape.entityRoot(context, method);
                if (domainEntity == null) continue;
                Set<String> collectionNames = collectionAttributeNames(domainEntity);
                if (!collectionNames.isEmpty()) context.evidence().markApplicable(true);
                String rootAlias = rootAlias(method.query());
                if (rootAlias == null) {
                    continue;
                }
                for (String path : joinFetchPaths(method.query())) {
                    String attributeName = directAttribute(rootAlias, path);
                    if (attributeName != null && collectionNames.contains(attributeName)) {
                        if (context.hibernateVersion().major() == null) {
                            context.missingEvidence();
                            continue;
                        }
                        if (context.hasHibernateCollectionFetchPaginationFix()
                                && !Boolean.TRUE.equals(method.evidence().limitInMemory())) {
                            context.missingEvidence();
                            continue;
                        }
                        details.add(method.description() + " pages a collection JOIN FETCH path " + path + ".");
                    }
                }
            }
        }
        return details;
    }

    static Set<String> collectionAttributeNames(HibernateEntityModel entity) {
        Set<String> names = new HashSet<>();
        for (HibernateAttributeModel attribute : entity.collectionAttributes()) {
            names.add(attribute.name());
        }
        return names;
    }

    /**
     * Returns true when the JPQL query hydrates the whole root entity (e.g. {@code select o from Entity o},
     * {@code select distinct o ...}, {@code select object(o) ...}, or an implicit {@code from Entity o}) rather than a
     * constructor expression, scalar value, aggregate, or multi-select projection.
     */
    static boolean selectsWholeRootEntity(String query) {
        query = HibernateQueryShape.lexical(query);
        if (query == null || query.isBlank()) {
            return false;
        }
        String alias = rootAlias(query);
        if (alias == null) {
            return false;
        }
        String trimmed = query.trim();
        Matcher select = SELECT_CLAUSE.matcher(trimmed);
        String selectItem;
        if (select.find()) {
            selectItem = select.group(1).trim();
        } else if (trimmed.regionMatches(true, 0, "from", 0, 4)) {
            return true;
        } else {
            return false;
        }
        String normalized = DISTINCT_PREFIX.matcher(selectItem).replaceFirst("").trim();
        Matcher object = OBJECT_WRAPPER.matcher(normalized);
        if (object.matches()) {
            normalized = object.group(1);
        }
        return normalized.equalsIgnoreCase(alias);
    }

    static Class<?> associationTargetType(HibernateAttributeModel attribute) {
        if (attribute.isCollectionAssociation()) {
            java.lang.reflect.Type generic = attribute.genericType();
            if (generic instanceof java.lang.reflect.ParameterizedType parameterized) {
                java.lang.reflect.Type[] args = parameterized.getActualTypeArguments();
                if (args.length > 0 && args[args.length - 1] instanceof Class<?> raw) {
                    return raw;
                }
            }
            return null;
        }
        return attribute.rawType();
    }

    static boolean implementsPersistable(Class<?> type) {
        if (type == null) return false;
        if ("org.springframework.data.domain.Persistable".equals(type.getName())) return true;
        for (Class<?> iface : type.getInterfaces()) if (implementsPersistable(iface)) return true;
        return implementsPersistable(type.getSuperclass());
    }

    static boolean hasCustomIdentifierGenerator(HibernateAttributeModel attribute) {
        if (!attribute.hasId()) return false;
        for (Annotation annotation : attribute.annotations()) {
            for (Annotation meta : annotation.annotationType().getAnnotations()) {
                if ("org.hibernate.annotations.IdGeneratorType"
                        .equals(meta.annotationType().getName())) return true;
            }
        }
        return false;
    }

    static boolean hasEnumeratedValue(HibernateAttributeModel attribute) {
        if (attribute.rawType() == null || !attribute.rawType().isEnum()) return false;
        for (java.lang.reflect.Field field : attribute.rawType().getDeclaredFields()) {
            for (Annotation annotation : field.getAnnotations()) {
                if ("jakarta.persistence.EnumeratedValue"
                        .equals(annotation.annotationType().getName())) return true;
            }
        }
        return false;
    }
}

final class EagerFetchRule extends AbstractHibernateRule {

    EagerFetchRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-FETCH-001",
                        "Eager fetching should stay explicit and bounded",
                        HibernateCategory.FETCHING,
                        "HIGH",
                        "Detects JPA associations and @ElementCollection attributes mapped with FetchType.EAGER,"
                                + " including default-eager to-one associations.",
                        "Prefer LAZY mappings and fetch required graphs or collection values explicitly with joins,"
                                + " entity graphs, or DTO queries.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : context.targets(
                    entity.attributes(), candidate -> candidate.isAssociation() || candidate.isElementCollection())) {
                Annotation association = attribute.associationAnnotation();
                if (association != null && "EAGER".equals(attribute.annotationValueName(association, "fetch"))) {
                    details.add(attribute.description() + " is mapped as FetchType.EAGER.");
                }
                Annotation elementCollection = attribute.elementCollectionAnnotation();
                if (elementCollection != null
                        && "EAGER".equals(attribute.annotationValueName(elementCollection, "fetch"))) {
                    details.add(attribute.description() + " is an @ElementCollection mapped as FetchType.EAGER.");
                }
            }
        }
        return violation(details);
    }
}

final class IdentityIdentifierRule extends AbstractHibernateRule {

    IdentityIdentifierRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-ID-001",
                        "Review IDENTITY insert-generation trade-offs",
                        HibernateCategory.IDENTIFIERS,
                        "MEDIUM",
                        "IDENTITY is supported but prevents batching the affected inserts. HIB-ID-006 owns the"
                                + " stronger finding when this unit has a batch size above one.",
                        "Keep IDENTITY when it fits the database and workload. Consider sequence allocation where"
                                + " supported and insert batching matters.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#identifiers-generators"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        Integer batchSize = context.observed()
                ? context.factorySettings().jdbcBatchSize()
                : context.firstIntegerProperty(
                        "spring.jpa.properties.hibernate.jdbc.batch_size", "hibernate.jdbc.batch_size");
        if (batchSize != null && batchSize > 1)
            return skipped("HIB-ID-006 owns the observed insert-batching conflict.");
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute :
                    context.targets(entity.attributes(), candidate -> candidate.generatedValueAnnotation() != null)) {
                Annotation generatedValue = attribute.generatedValueAnnotation();
                if (generatedValue == null) {
                    continue;
                }
                String strategy = attribute.annotationValueName(generatedValue, "strategy");
                if ("IDENTITY".equals(strategy)) {
                    details.add(attribute.description() + " uses GenerationType.IDENTITY.");
                }
            }
        }
        return violation(details);
    }
}

final class TableIdentifierRule extends AbstractHibernateRule {

    TableIdentifierRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-ID-002",
                        "Review table-based identifier allocation",
                        HibernateCategory.IDENTIFIERS,
                        "MEDIUM",
                        "Reviews TABLE generator declarations and their allocation-table trade-off; actual contention"
                                + " and optimizer behavior are not measured.",
                        "Compare generator and pooling choices against the database and insert workload. The"
                                + " declaration does not demonstrate contention, and pooled table allocation may be"
                                + " intentional.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#identifiers-generators"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute :
                    context.targets(entity.attributes(), candidate -> candidate.generatedValueAnnotation() != null)) {
                Annotation generatedValue = attribute.generatedValueAnnotation();
                if (generatedValue == null) {
                    continue;
                }
                String strategy = attribute.annotationValueName(generatedValue, "strategy");
                if ("TABLE".equals(strategy)) {
                    details.add(attribute.description() + " uses GenerationType.TABLE.");
                }
            }
        }
        return violation(details);
    }
}

final class SequenceAllocationSizeRule extends AbstractHibernateRule {

    SequenceAllocationSizeRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-ID-003",
                        "Review sequence allocation declarations",
                        HibernateCategory.IDENTIFIERS,
                        "MEDIUM",
                        "Detects @SequenceGenerator declarations with allocationSize=1.",
                        "Review which generator is actually selected before tuning allocation; larger allocations"
                                + " require optimizer/schema interoperability and are not universally mandatory.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#identifiers-generators-sequence"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            Annotation entitySequence = entity.annotation("jakarta.persistence.SequenceGenerator");
            if (entitySequence != null) context.evidence().markApplicable(true);
            if (allocationSizeIsOne(entitySequence, entity)) {
                details.add(entity.name() + " declares @SequenceGenerator(allocationSize=1).");
            }
            for (HibernateAttributeModel attribute : context.targets(
                    entity.attributes(), candidate -> candidate.sequenceGeneratorAnnotation() != null)) {
                Annotation sequenceGenerator = attribute.sequenceGeneratorAnnotation();
                if (allocationSizeIsOne(sequenceGenerator, entity)) {
                    details.add(attribute.description() + " declares @SequenceGenerator(allocationSize=1).");
                }
            }
        }
        return violation(details);
    }

    private boolean allocationSizeIsOne(Annotation annotation, HibernateEntityModel entity) {
        if (annotation == null) {
            return false;
        }
        Integer allocationSize = entity.annotationIntValue(annotation, "allocationSize");
        return Integer.valueOf(1).equals(allocationSize);
    }
}

final class UnidirectionalOneToManyRule extends AbstractHibernateRule {

    UnidirectionalOneToManyRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-MAP-001",
                        "One-to-many associations should be bidirectional or join-column based",
                        HibernateCategory.MAPPING,
                        "MEDIUM",
                        "Reviews declared unidirectional @OneToMany mappings without mappedBy or join columns."
                                + " Join-table mutation cost depends on effective mapping and workload.",
                        "Prefer a bidirectional association: put @ManyToOne on the child and @OneToMany(mappedBy=...)"
                                + " on the parent so the child's foreign key owns the relationship. If a unidirectional"
                                + " mapping is intentional, add @JoinColumn to drop the join table (note the extra UPDATE"
                                + " statements flagged by HIB-MAP-020).",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#associations-one-to-many"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute :
                    context.targets(entity.attributes(), HibernateAttributeModel::isOneToMany)) {
                Annotation oneToMany = attribute.oneToManyAnnotation();
                if (!attribute.isOneToMany() || oneToMany == null) {
                    continue;
                }
                String mappedBy = attribute.annotationStringValue(oneToMany, "mappedBy");
                if ((mappedBy == null || mappedBy.isBlank()) && !attribute.hasJoinColumn()) {
                    details.add(
                            attribute.description() + " is unidirectional @OneToMany without mappedBy or @JoinColumn.");
                }
            }
        }
        return violation(details);
    }
}

final class ManyToManyListRule extends AbstractHibernateRule {

    ManyToManyListRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-MAP-002",
                        "Review many-to-many list semantics",
                        HibernateCategory.MAPPING,
                        "MEDIUM",
                        "Reviews @ManyToMany Lists without an explicit order column. Ordered lists are intentional"
                                + " mappings; effective default list semantics and mutation SQL are not reconstructed.",
                        "Preserve domain ordering and benchmark mutation behavior. Consider Set only when its"
                                + " uniqueness semantics fit, or a link entity when the relationship has its own"
                                + " attributes or lifecycle.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#associations-many-to-many"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute :
                    context.targets(entity.attributes(), HibernateAttributeModel::isManyToMany)) {
                if (attribute.isManyToMany() && attribute.isListAttribute() && !attribute.hasOrderColumn()) {
                    String ordering = attribute.hasOrderColumn()
                            ? " with @OrderColumn; preserve the order only when it is domain-significant."
                            : " without @OrderColumn.";
                    details.add(attribute.description()
                            + " is @ManyToMany and declared as a List"
                            + ordering
                            + " Consider a link entity when link lifecycle or attributes matter.");
                }
            }
        }
        return violation(details);
    }
}

final class ManyToManyRemoveCascadeRule extends AbstractHibernateRule {

    ManyToManyRemoveCascadeRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-MAP-004",
                        "Many-to-many associations should not cascade remove",
                        HibernateCategory.MAPPING,
                        "HIGH",
                        "Detects @ManyToMany mappings whose cascade list contains REMOVE or ALL.",
                        "Remove REMOVE/ALL cascades from many-to-many associations; model the join table as an entity"
                                + " when lifecycle ownership is needed.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#pc-cascade"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute :
                    context.targets(entity.attributes(), HibernateAttributeModel::isManyToMany)) {
                Annotation manyToMany = attribute.manyToManyAnnotation();
                if (manyToMany != null && hasRemoveCascade(attribute, manyToMany)) {
                    details.add(attribute.description() + " cascades REMOVE/ALL across @ManyToMany.");
                }
            }
        }
        return violation(details);
    }

    private boolean hasRemoveCascade(HibernateAttributeModel attribute, Annotation annotation) {
        return attribute.annotationEnumArrayContains(annotation, "cascade", "REMOVE")
                || attribute.annotationEnumArrayContains(annotation, "cascade", "ALL");
    }
}

final class ManyToOneRemoveCascadeRule extends AbstractHibernateRule {

    ManyToOneRemoveCascadeRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-MAP-005",
                        "Many-to-one associations should not cascade remove",
                        HibernateCategory.MAPPING,
                        "HIGH",
                        "Detects @ManyToOne mappings whose cascade list contains REMOVE or ALL.",
                        "Remove REMOVE/ALL cascades from many-to-one associations so deletes do not propagate from"
                                + " children to shared parents.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#pc-cascade"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute :
                    context.targets(entity.attributes(), candidate -> candidate.manyToOneAnnotation() != null)) {
                Annotation manyToOne = attribute.manyToOneAnnotation();
                if (manyToOne != null && hasRemoveCascade(attribute, manyToOne)) {
                    details.add(attribute.description() + " cascades REMOVE/ALL across @ManyToOne.");
                }
            }
        }
        return violation(details);
    }

    private boolean hasRemoveCascade(HibernateAttributeModel attribute, Annotation annotation) {
        return attribute.annotationEnumArrayContains(annotation, "cascade", "REMOVE")
                || attribute.annotationEnumArrayContains(annotation, "cascade", "ALL");
    }
}

final class OneToOneWithoutMapsIdRule extends AbstractHibernateRule {

    OneToOneWithoutMapsIdRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-MAP-006",
                        "One-to-one associations should prefer shared primary keys",
                        HibernateCategory.MAPPING,
                        "MEDIUM",
                        "Detects owning-side @OneToOne mappings that do not use @MapsId.",
                        "Use @MapsId for dependent one-to-one entities when the child row has the same lifecycle and"
                                + " identifier as the parent.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#identifiers-derived"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> dependentDetails = new ArrayList<>();
        List<String> plainDetails = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute :
                    context.targets(entity.attributes(), candidate -> candidate.oneToOneAnnotation() != null)) {
                Annotation oneToOne = attribute.oneToOneAnnotation();
                if (oneToOne == null || attribute.hasMapsId() || attribute.hasId()) {
                    continue;
                }
                String mappedBy = attribute.annotationStringValue(oneToOne, "mappedBy");
                if (mappedBy != null && !mappedBy.isBlank()) {
                    continue;
                }
                if (hasDependentSignal(attribute, oneToOne)) {
                    dependentDetails.add(attribute.description()
                            + " is an owning @OneToOne that looks lifecycle-dependent (optional=false or"
                            + " cascade REMOVE/ALL) but does not use @MapsId.");
                } else {
                    plainDetails.add(attribute.description()
                            + " is an owning @OneToOne without @MapsId; consider a shared primary key when the"
                            + " child shares the parent's lifecycle and identifier.");
                }
            }
        }
        if (!dependentDetails.isEmpty()) {
            List<String> all = new ArrayList<>(dependentDetails);
            all.addAll(plainDetails);
            return violation(HibernateRuleSupport.MEDIUM, all);
        }
        return violation(HibernateRuleSupport.LOW, plainDetails);
    }

    private boolean hasDependentSignal(HibernateAttributeModel attribute, Annotation oneToOne) {
        Boolean optional = attribute.annotationBooleanValue(oneToOne, "optional");
        boolean mandatory = Boolean.FALSE.equals(optional);
        boolean cascadesRemove = attribute.annotationEnumArrayContains(oneToOne, "cascade", "REMOVE")
                || attribute.annotationEnumArrayContains(oneToOne, "cascade", "ALL");
        return mandatory || cascadesRemove;
    }
}

final class TablePerClassInheritanceRule extends AbstractHibernateRule {

    TablePerClassInheritanceRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-MAP-007",
                        "Review TABLE_PER_CLASS polymorphic queries",
                        HibernateCategory.MAPPING,
                        "INFO",
                        "Detects @Inheritance(strategy = TABLE_PER_CLASS), which requires UNION queries for"
                                + " polymorphic loads.",
                        "Evaluate the polymorphic query workload before changing inheritance strategy. Subtype-only"
                                + " access can make TABLE_PER_CLASS intentional; the declaration does not prove expensive"
                                + " queries are executed.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#entity-inheritance"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.targets(context.entities())) {
            Annotation inheritance = entity.annotation("jakarta.persistence.Inheritance");
            String strategy = entity.annotationValueName(inheritance, "strategy");
            if ("TABLE_PER_CLASS".equals(strategy)) {
                details.add(entity.name() + " uses InheritanceType.TABLE_PER_CLASS.");
            }
        }
        return violation(details);
    }
}

final class NotFoundIgnoreRule extends AbstractHibernateRule {

    NotFoundIgnoreRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-MAP-008",
                        "@NotFound(IGNORE) should be reviewed",
                        HibernateCategory.MAPPING,
                        "MEDIUM",
                        "Detects Hibernate @NotFound(action = IGNORE), which hides missing references and forces eager"
                                + " resolution.",
                        "Fix referential integrity or model optional data explicitly instead of suppressing missing"
                                + " target rows.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#associations-not-found"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : context.targets(
                    entity.attributes(),
                    candidate -> candidate.isAssociation()
                            || candidate.annotation("org.hibernate.annotations.NotFound") != null)) {
                Annotation notFound = attribute.annotation("org.hibernate.annotations.NotFound");
                if ("IGNORE".equals(attribute.annotationValueName(notFound, "action"))) {
                    details.add(attribute.description() + " uses @NotFound(action=IGNORE).");
                }
            }
        }
        return violation(details);
    }
}

final class OptionalPersistentAttributeRule extends AbstractHibernateRule {

    OptionalPersistentAttributeRule() {
        super(new HibernateRuleDefinition(
                "HIB-MAP-009",
                "Persistent attributes should not be Optional",
                HibernateCategory.MAPPING,
                "MEDIUM",
                "Detects mapped attributes declared as java.util.Optional.",
                "Map the underlying nullable type and expose Optional from a non-persistent getter if desired.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : context.targets(entity.attributes())) {
                if (attribute.isOptionalAttribute()) {
                    details.add(attribute.description() + " is mapped as java.util.Optional.");
                }
            }
        }
        return violation(details);
    }
}

final class MultipleBagCollectionRule extends AbstractHibernateRule {

    MultipleBagCollectionRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-FETCH-004",
                        "Review entities with multiple bag collections",
                        HibernateCategory.FETCHING,
                        "INFO",
                        "Detects entities with two or more unordered List/Collection associations (bags). Declaring"
                                + " multiple bags is common and safe on its own - the risk is only realized if two of them"
                                + " are ever join-fetched in the same query, which throws MultipleBagFetchException."
                                + " HIB-QUERY-007 already flags that specific case (JOIN FETCH of 2+ collections in the"
                                + " same query); this is an informational reminder to keep it that way.",
                        "No action is required unless you plan to fetch these together: never JOIN FETCH more than one"
                                + " of these collections in the same query. Add @OrderColumn when list order is"
                                + " persistent, or use Set<> if you do need to fetch two of them eagerly in one query.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching-strategies"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            List<String> bagNames = context.targets(entity.collectionAttributes()).stream()
                    .filter(HibernateAttributeModel::isBagAttribute)
                    .map(HibernateAttributeModel::name)
                    .toList();
            if (bagNames.size() >= 2) {
                details.add(entity.name() + " has " + bagNames.size() + " bag collections: "
                        + String.join(", ", bagNames) + ".");
            }
        }
        return violation(details);
    }
}

final class OrdinalEnumRule extends AbstractHibernateRule {

    OrdinalEnumRule() {
        super(new HibernateRuleDefinition(
                "HIB-MAP-003",
                "Enum attributes should declare an explicit storage strategy",
                HibernateCategory.MAPPING,
                "MEDIUM",
                "Reviews enum declarations without explicit storage metadata, respecting local converters and"
                        + " @EnumeratedValue. Auto-apply converters and XML mappings remain unobserved, so ordinal storage"
                        + " is not established.",
                "Declare the enum mapping explicitly. Prefer STRING, a database-native enum type, or a converter with"
                        + " stable database codes.",
                "https://vladmihalcea.com/the-best-way-to-map-an-enum-type-with-jpa-and-hibernate/"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute :
                    context.targets(entity.attributes(), HibernateAttributeModel::isEnumAttribute)) {
                if (!attribute.isEnumAttribute()) {
                    continue;
                }
                Annotation enumerated = attribute.enumeratedAnnotation();
                Annotation convert = attribute.annotation("jakarta.persistence.Convert");
                boolean activeConverter = convert != null
                        && !Boolean.TRUE.equals(attribute.annotationBooleanValue(convert, "disableConversion"));
                if (enumerated == null
                        && !activeConverter
                        && !HibernateRuleModelSupport.hasEnumeratedValue(attribute)) {
                    details.add(attribute.description()
                            + " has no explicit enum storage annotation; review converters and effective"
                            + " mapping before assuming ORDINAL.");
                }
            }
        }
        return violation(details);
    }
}

final class ExplicitOrdinalEnumRule extends AbstractHibernateRule {

    ExplicitOrdinalEnumRule() {
        super(new HibernateRuleDefinition(
                "HIB-MAP-022",
                "Explicit ordinal enum mappings should be reviewed",
                HibernateCategory.MAPPING,
                "INFO",
                "Detects enum attributes explicitly mapped with @Enumerated(ORDINAL).",
                "Prefer STRING, a database-native enum type, or a converter with stable database codes. Keep ORDINAL"
                        + " only when append-only enum ordering is an explicit schema contract.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute :
                    context.targets(entity.attributes(), HibernateAttributeModel::isEnumAttribute)) {
                Annotation enumerated = attribute.enumeratedAnnotation();
                if (attribute.isEnumAttribute()
                        && enumerated != null
                        && !HibernateRuleModelSupport.hasEnumeratedValue(attribute)
                        && "ORDINAL".equals(attribute.annotationValueName(enumerated, "value"))) {
                    details.add(attribute.description() + " explicitly uses EnumType.ORDINAL.");
                }
            }
        }
        return violation(details);
    }
}

final class OpenInViewRule extends AbstractHibernateRule {

    @Override
    public boolean applicationWide() {
        return true;
    }

    OpenInViewRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CONFIG-001",
                        "Open Session in View should be disabled",
                        HibernateCategory.CONFIGURATION,
                        "MEDIUM",
                        "Reviews positively observed Open Session in View activation in Spring servlet applications."
                                + " An absent property alone does not prove activation or JDBC connection lifetime.",
                        "Set spring.jpa.open-in-view=false and fetch data inside transactional service boundaries.",
                        "https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.jpa-and-spring-data.open-in-view"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (!context.isOpenInViewApplicable()) {
            return skipped("Open Session in View is not applicable outside a Spring servlet web application.");
        }
        Boolean value = context.booleanProperty("spring.jpa.open-in-view");
        if (value == null) {
            context.missingEvidence();
            return skipped("Open Session in View activation is unknown.");
        }
        if (Boolean.FALSE.equals(value)) {
            return pass();
        }
        return violation(
                HibernateRuleSupport.MEDIUM,
                "Open Session in View is active; a persistence context may remain available outside service"
                        + " transactions.");
    }
}

final class MissingBatchFetchRule extends AbstractHibernateRule {

    MissingBatchFetchRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-FETCH-002",
                        "Batch fetching should cover lazy secondary-select associations",
                        HibernateCategory.FETCHING,
                        "INFO",
                        "Detects lazy to-one and collection associations that can initialize through secondary selects"
                                + " without hibernate.default_batch_fetch_size or an applicable @BatchSize.",
                        "Set a bounded hibernate.default_batch_fetch_size or targeted @BatchSize for associations"
                                + " traversed across multiple owner rows; use explicit fetch plans or paged queries for a"
                                + " single oversized collection.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching-batch"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (!context.hasAssociations()) {
            return skipped("No mapped associations were detected.");
        }
        Integer defaultBatchSize = context.defaultBatchFetchSize();
        List<HibernateAttributeModel> candidates = context.targets(
                context.entities().stream()
                        .flatMap(entity -> entity.attributes().stream())
                        .toList(),
                this::isBatchFetchCandidate);
        context.evidence().markApplicable(!candidates.isEmpty());
        if (defaultBatchSize != null && defaultBatchSize > 0) {
            return pass();
        }
        Map<String, HibernateEntityModel> entitiesByJavaType =
                HibernateRuleModelSupport.entitiesByJavaType(context.entities());
        List<String> details = new ArrayList<>();
        for (HibernateAttributeModel attribute : candidates) {
            if (isCoveredByBatchSize(attribute, entitiesByJavaType)) {
                continue;
            }
            details.add(attribute.description()
                    + " can initialize through secondary selects without a global batch-fetch size or"
                    + " applicable @BatchSize.");
        }
        return violation(details);
    }

    private boolean isBatchFetchCandidate(HibernateAttributeModel attribute) {
        Annotation association = attribute.associationAnnotation();
        if (association == null || hasNonBatchFetchMode(attribute)) {
            return false;
        }
        String fetch = attribute.annotationValueName(association, "fetch");
        if ("EAGER".equals(fetch)) {
            return false;
        }
        return attribute.isCollectionAssociation() || (attribute.isToOneAssociation() && "LAZY".equals(fetch));
    }

    private boolean hasNonBatchFetchMode(HibernateAttributeModel attribute) {
        Annotation fetch = attribute.fetchAnnotation();
        if (fetch == null) {
            return false;
        }
        String mode = attribute.annotationValueName(fetch, "value");
        return "JOIN".equals(mode) || "SUBSELECT".equals(mode);
    }

    private boolean isCoveredByBatchSize(
            HibernateAttributeModel attribute, Map<String, HibernateEntityModel> entitiesByJavaType) {
        if (attribute.hasBatchSizeAnnotation()) {
            return true;
        }
        if (!attribute.isToOneAssociation()) {
            return false;
        }
        Class<?> targetType = HibernateRuleModelSupport.associationTargetType(attribute);
        HibernateEntityModel target = targetType == null ? null : entitiesByJavaType.get(targetType.getName());
        return target != null && target.hasBatchSizeAnnotation();
    }
}

final class CollectionJoinFetchPageableRule extends AbstractHibernateRule {

    CollectionJoinFetchPageableRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-FETCH-003",
                        "Collection fetch joins should not be paged directly",
                        HibernateCategory.FETCHING,
                        "HIGH",
                        "Detects Spring Data JPQL queries that combine Pageable with a collection JOIN FETCH.",
                        "Page root ids first, then fetch the required collection graph in a second query inside the"
                                + " same transaction.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#hql-fetching"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.repositories().isEmpty()) {
            return skipped("No repository metadata was detected.");
        }
        return violation(HibernateRuleModelSupport.paginatedCollectionFetchFindings(context));
    }
}

final class LazyLoadNoTransRule extends AbstractHibernateRule {

    LazyLoadNoTransRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CONFIG-003",
                        "Lazy loading outside transactions should stay disabled",
                        HibernateCategory.CONFIGURATION,
                        "HIGH",
                        "Detects hibernate.enable_lazy_load_no_trans=true.",
                        "Remove this setting and fetch required data inside transaction boundaries with explicit fetch"
                                + " plans or DTO queries.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.isPropertyTrue(
                "spring.jpa.properties.hibernate.enable_lazy_load_no_trans", "hibernate.enable_lazy_load_no_trans")) {
            return violation(List.of("hibernate.enable_lazy_load_no_trans=true is enabled."));
        }
        return pass();
    }
}

final class JdbcBatchSizeRule extends AbstractHibernateRule {

    JdbcBatchSizeRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CONFIG-004",
                        "JDBC batching should be configured for writes",
                        HibernateCategory.CONFIGURATION,
                        "INFO",
                        "Detects hibernate.jdbc.batch_size values below 2, which cannot combine multiple statements"
                                + " into one JDBC batch.",
                        "Benchmark a bounded JDBC batch size for write workloads; no single batch size is optimal for"
                                + " every application.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#batch-session-batch"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        Integer batchSize = context.firstIntegerProperty(
                "spring.jpa.properties.hibernate.jdbc.batch_size", "hibernate.jdbc.batch_size");
        if (batchSize != null && batchSize > 1) {
            return pass();
        }
        return violation(List.of("hibernate.jdbc.batch_size is not configured with a value greater than 1."));
    }
}

final class OrderedBatchingRule extends AbstractHibernateRule {

    OrderedBatchingRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CONFIG-005",
                        "JDBC batching should order inserts and updates",
                        HibernateCategory.CONFIGURATION,
                        "INFO",
                        "Detects configured JDBC batching without hibernate.order_inserts and hibernate.order_updates.",
                        "Benchmark insert/update ordering: grouping can improve batches but sorting also has a cost."
                                + " Factory defaults do not prove per-session behavior.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#batch-session-batch"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        Integer batchSize = context.firstIntegerProperty(
                "spring.jpa.properties.hibernate.jdbc.batch_size", "hibernate.jdbc.batch_size");
        if (batchSize == null || batchSize <= 1) {
            return skipped("JDBC batching is not configured.");
        }
        List<String> details = new ArrayList<>();
        if (context.observed()) {
            Boolean inserts = context.factorySettings().orderInserts();
            Boolean updates = context.factorySettings().orderUpdates();
            if (inserts == null || updates == null) context.missingEvidence();
            if (Boolean.FALSE.equals(inserts)) details.add("The factory insert-ordering default is disabled.");
            if (Boolean.FALSE.equals(updates)) details.add("The factory update-ordering default is disabled.");
            return violation(details);
        }
        if (!context.isPropertyTrue("spring.jpa.properties.hibernate.order_inserts", "hibernate.order_inserts")) {
            details.add("hibernate.order_inserts is not enabled.");
        }
        if (!context.isPropertyTrue("spring.jpa.properties.hibernate.order_updates", "hibernate.order_updates")) {
            details.add("hibernate.order_updates is not enabled.");
        }
        return violation(details);
    }
}

final class SlowQueryLogRule extends AbstractHibernateRule {

    SlowQueryLogRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CONFIG-006",
                        "Slow query logging should be available in development",
                        HibernateCategory.CONFIGURATION,
                        "INFO",
                        "Detects missing Hibernate slow-query threshold configuration.",
                        "Configure a bounded slow-query threshold in development and staging profiles to surface"
                                + " expensive SQL early.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#statistics"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        Integer threshold = context.firstIntegerProperty(
                "spring.jpa.properties.hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS",
                "hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS",
                "spring.jpa.properties.hibernate.log_slow_query",
                "hibernate.log_slow_query");
        if (threshold != null && threshold > 0) {
            return pass();
        }
        return violation(List.of("No positive Hibernate slow-query threshold was detected."));
    }
}

final class HibernateStatisticsRule extends AbstractHibernateRule {

    HibernateStatisticsRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CONFIG-007",
                        "Hibernate statistics should be enabled when tuning",
                        HibernateCategory.CONFIGURATION,
                        "INFO",
                        "Detects hibernate.generate_statistics not being enabled for the current environment.",
                        "Enable statistics in development or performance-test profiles when investigating query"
                                + " counts, cache efficiency, and fetch plans.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#statistics"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.isPropertyTrue(
                "spring.jpa.properties.hibernate.generate_statistics", "hibernate.generate_statistics")) {
            return pass();
        }
        return violation(List.of("hibernate.generate_statistics is not enabled."));
    }
}

final class ProviderDisablesAutocommitRule extends AbstractHibernateRule {

    ProviderDisablesAutocommitRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CONFIG-008",
                        "Connection providers should disable auto-commit explicitly",
                        HibernateCategory.CONFIGURATION,
                        "INFO",
                        "Detects resource-local configurations where hibernate.connection.provider_disables_autocommit"
                                + " is not enabled.",
                        "Consider hibernate.connection.provider_disables_autocommit=true only for verified"
                                + " resource-local handling when the provider guarantees auto-commit is disabled."
                                + " Framework property presence does not establish that guarantee.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#database-connectionprovider"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.observed()) {
            context.missingEvidence();
            return skipped("Selected pool auto-commit and resource-local guarantees were not observed; no connection is"
                    + " acquired.");
        }
        if (context.isPropertyTrue("spring.jta.enabled")
                || "JTA"
                        .equalsIgnoreCase(context.firstProperty(
                                "spring.jpa.properties.jakarta.persistence.transactionType",
                                "spring.jpa.properties.javax.persistence.transactionType",
                                "jakarta.persistence.transactionType",
                                "javax.persistence.transactionType"))) {
            return skipped("JTA transaction management was detected.");
        }
        if (context.isPropertyTrue(
                "spring.jpa.properties.hibernate.connection.provider_disables_autocommit",
                "hibernate.connection.provider_disables_autocommit")) {
            return pass();
        }
        Boolean hikariAutoCommit = context.booleanProperty("spring.datasource.hikari.auto-commit");
        if (Boolean.FALSE.equals(hikariAutoCommit)) {
            return violation(List.of("spring.datasource.hikari.auto-commit=false but"
                    + " hibernate.connection.provider_disables_autocommit is not enabled, so Hibernate"
                    + " acquires the JDBC connection eagerly on transaction start."));
        }
        return skipped("Auto-commit handling could not be confirmed; set"
                + " hibernate.connection.provider_disables_autocommit=true when the connection pool disables"
                + " auto-commit.");
    }
}

final class InClausePaddingRule extends AbstractHibernateRule {

    private static final Pattern QUOTED_LITERAL = Pattern.compile("'(?:''|[^'])*'");
    private static final Pattern IN_PREDICATE = Pattern.compile("(?is)\\b(?:not\\s+)?in\\s*(?:\\(|:|\\?)");

    InClausePaddingRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CONFIG-009",
                        "Collection-parameter queries should use IN-clause padding",
                        HibernateCategory.CONFIGURATION,
                        "INFO",
                        "Detects repository queries with collection parameters when"
                                + " hibernate.query.in_clause_parameter_padding is disabled.",
                        "Enable IN-clause parameter padding when variable-length IN predicates are common and the"
                                + " database benefits from plan reuse.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#configurations-query"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.repositories().isEmpty()) return skipped("No repository query metadata is available.");
        boolean padding = context.isPropertyTrue(
                "spring.jpa.properties.hibernate.query.in_clause_parameter_padding",
                "hibernate.query.in_clause_parameter_padding");
        List<HibernateRepositoryMethodModel> methods = context.targets(
                context.repositories().stream()
                        .flatMap(repository -> repository.methods().stream())
                        .toList(),
                candidate ->
                        !candidate.nativeQuery() && candidate.query() != null && candidate.hasCollectionParameter());
        context.evidence().markApplicable(!methods.isEmpty());
        if (padding) {
            return pass();
        }
        List<String> details = new ArrayList<>();
        for (HibernateRepositoryMethodModel method : methods) {
            String query = HibernateQueryShape.lexical(method.query());
            if (query == null || HibernateQueryShape.root(context, method, false) == null) continue;
            boolean bound = !context.observed()
                    || method.evidence().collectionParameterBindings().stream()
                            .anyMatch(binding -> Pattern.compile(
                                            "(?i)\\bin\\s*\\(?\\s*" + Pattern.quote(binding) + "(?![\\w])")
                                    .matcher(query)
                                    .find());
            if (bound && hasInPredicate(query)) {
                details.add(method.description() + " has a collection parameter in an IN predicate.");
            }
        }
        return violation(details);
    }

    private boolean hasInPredicate(String query) {
        String stripped = QUOTED_LITERAL.matcher(query).replaceAll(" ");
        return IN_PREDICATE.matcher(stripped).find();
    }
}

final class QueryCacheRegionFactoryRule extends AbstractHibernateRule {

    QueryCacheRegionFactoryRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CONFIG-010",
                        "Query caching requires effective region support",
                        HibernateCategory.CONFIGURATION,
                        "HIGH",
                        "Checks the observed query-cache flag against the selected region factory. Entity"
                                + " second-level-cache eligibility is a separate concern and explicit factory property"
                                + " absence is not a failure.",
                        "Provide a supported query-cache region service or disable query caching; measure cacheable"
                                + " query use and entity caching independently.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#caching-query"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.observed()) {
            if (!context.required(context.factorySettings().queryCache())) return pass();
            if (context.factorySettings().regionFactory() == HibernateFactorySettings.RegionFactory.UNKNOWN) {
                context.missingEvidence();
                return skipped("Effective query-cache region support is unknown.");
            }
            return context.factorySettings().regionFactory() == HibernateFactorySettings.RegionFactory.NONE
                    ? violation(
                            List.of("Query caching is enabled but the selected region factory provides no caching."))
                    : pass();
        }
        if (!context.isPropertyTrue(
                "spring.jpa.properties.hibernate.cache.use_query_cache", "hibernate.cache.use_query_cache")) {
            return pass();
        }
        String regionFactory = context.firstProperty(
                "spring.jpa.properties.hibernate.cache.region.factory_class", "hibernate.cache.region.factory_class");
        boolean secondLevelCacheDisabled = context.isPropertyFalse(
                "spring.jpa.properties.hibernate.cache.use_second_level_cache",
                "hibernate.cache.use_second_level_cache");
        if (regionFactory == null || secondLevelCacheDisabled) {
            return violation(List.of("Query cache is enabled without an effective second-level cache region factory."));
        }
        return pass();
    }
}

final class CacheableWithoutCacheStrategyRule extends AbstractHibernateRule {

    CacheableWithoutCacheStrategyRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CONFIG-011",
                        "Review effective cache concurrency strategy",
                        HibernateCategory.CONFIGURATION,
                        "MEDIUM",
                        "Provider defaults can supply a valid access strategy without @Cache. This check skips when"
                                + " effective eligibility/access strategy is unavailable and never requires an annotation"
                                + " solely for explicitness.",
                        "Review provider-selected entity eligibility, access strategy and data mutability before"
                                + " adding annotations or changing cache use.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#caching"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.entities().stream()
                .noneMatch(entity -> entity.isJpaCacheable() && !entity.hasHibernateCacheAnnotation())) return pass();
        if (context.observed()) {
            if (!context.required(context.factorySettings().secondLevelCache()))
                return skipped("Unit cache is disabled.");
            context.missingEvidence();
            return skipped(
                    "Provider-selected access strategy and entity eligibility are not observed; explicit @Cache is not"
                            + " required.");
        }
        String regionFactory = context.firstProperty(
                "spring.jpa.properties.hibernate.cache.region.factory_class", "hibernate.cache.region.factory_class");
        if (regionFactory == null
                && !context.isPropertyTrue(
                        "spring.jpa.properties.hibernate.cache.use_second_level_cache",
                        "hibernate.cache.use_second_level_cache")) {
            return skipped("Second-level caching is not configured.");
        }
        if (hasDefaultCacheStrategy(context)) return pass();
        context.missingEvidence();
        return skipped("Provider-selected cache strategy is unknown.");
    }

    private boolean hasDefaultCacheStrategy(HibernateContext context) {
        String value = context.firstProperty(
                "spring.jpa.properties.hibernate.cache.default_cache_concurrency_strategy",
                "hibernate.cache.default_cache_concurrency_strategy");
        if (value == null) {
            return false;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT).replace('-', '_')) {
            case "READ_ONLY", "NONSTRICT_READ_WRITE", "READ_WRITE", "TRANSACTIONAL" -> true;
            default -> false;
        };
    }
}

final class RiskyDdlAutoRule extends AbstractHibernateRule {

    RiskyDdlAutoRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CONFIG-002",
                        "Schema generation should not mutate non-test databases",
                        HibernateCategory.CONFIGURATION,
                        "INFO",
                        "Reviews the unit's normalized database action: drop and drop-and-create are destructive;"
                                + " create-only and update may change schema objects without guaranteeing deletion.",
                        "Use versioned migrations for shared databases and reserve ddl-auto=create/create-drop/update"
                                + " for disposable test environments.",
                        "https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.jpa-and-spring-data.creating-and-dropping"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        context.evidence().markApplicable(!context.entities().isEmpty());
        String ddlAuto = context.observed()
                ? null
                : context.firstProperty(
                        "spring.jpa.hibernate.ddl-auto",
                        "spring.jpa.properties.hibernate.hbm2ddl.auto",
                        "hibernate.hbm2ddl.auto");
        HibernateFactorySettings.SchemaAction action = context.observed()
                ? context.factorySettings().schemaAction()
                : HibernateFactorySettings.SchemaAction.hibernate(ddlAuto);
        if (action == HibernateFactorySettings.SchemaAction.UNKNOWN) {
            context.missingEvidence();
            return skipped("Effective database schema action is unknown.");
        }
        if (action == HibernateFactorySettings.SchemaAction.NONE) return pass();
        ddlAuto = action.name();
        String[] profiles = context.activeProfiles().toArray(new String[0]);
        boolean creates = action == HibernateFactorySettings.SchemaAction.DROP
                || action == HibernateFactorySettings.SchemaAction.DROP_AND_CREATE;
        // Highest-risk profile wins: a production-like profile is dangerous even if a test/dev profile is also active.
        if (context.isProductionProfileActive()) {
            String severity = creates ? HibernateRuleSupport.CRITICAL : HibernateRuleSupport.HIGH;
            return violation(
                    severity,
                    "ddl-auto is set to " + ddlAuto
                            + " while a production-like profile is active, so application startup can "
                            + (creates ? "drop schema objects" : "change schema objects without a guaranteed drop")
                            + ".");
        }
        if (hasTestProfile(profiles)) {
            return pass();
        }
        if (isDisposableProfile(profiles)) {
            return violation(
                    HibernateRuleSupport.INFO,
                    "ddl-auto is set to " + ddlAuto
                            + " under a dev/local profile; this is fine for a disposable database but must not reach"
                            + " shared or production environments.");
        }
        return violation(
                HibernateRuleSupport.MEDIUM,
                "ddl-auto is set to " + ddlAuto
                        + " with no profile pinning it to a disposable database; use versioned migrations for any"
                        + " shared database.");
    }

    private boolean hasTestProfile(String[] profiles) {
        for (String profile : profiles) {
            String normalized = profile.toLowerCase(Locale.ROOT);
            if (normalized.equals("test") || normalized.startsWith("test-") || normalized.endsWith("-test")) {
                return true;
            }
        }
        return false;
    }

    private boolean isDisposableProfile(String[] profiles) {
        for (String profile : profiles) {
            String normalized = profile.toLowerCase(Locale.ROOT);
            if (normalized.equals("dev")
                    || normalized.equals("local")
                    || normalized.startsWith("dev-")
                    || normalized.endsWith("-dev")
                    || normalized.startsWith("local-")
                    || normalized.endsWith("-local")) {
                return true;
            }
        }
        return false;
    }
}

final class EqualsHashCodePairRule extends AbstractHibernateRule {

    EqualsHashCodePairRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-ENTITY-001",
                        "Entities should override equals and hashCode consistently",
                        HibernateCategory.ENTITY_DESIGN,
                        "INFO",
                        "Detects entities that override equals without hashCode, or hashCode without equals.",
                        "Implement equals and hashCode as a pair, and review generated identifier semantics before"
                                + " using entities in sets or maps.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#mapping-model-pojo-equalshashcode"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.targets(context.entities())) {
            if (entity.overridesEquals() != entity.overridesHashCode()) {
                details.add(entity.name() + " overrides "
                        + (entity.overridesEquals() ? "equals but not hashCode." : "hashCode but not equals."));
            }
        }
        return violation(details);
    }
}

final class OptimisticLockingDynamicUpdateRule extends AbstractHibernateRule {

    OptimisticLockingDynamicUpdateRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-ENTITY-002",
                        "Versionless optimistic locking should use dynamic updates",
                        HibernateCategory.ENTITY_DESIGN,
                        "MEDIUM",
                        "Detects Hibernate @OptimisticLocking(DIRTY/ALL) without @DynamicUpdate.",
                        "Add @DynamicUpdate when using versionless optimistic locking so UPDATE statements include the"
                                + " intended changed columns.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#locking-optimistic-versionless"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.targets(
                context.entities(),
                candidate -> candidate.annotationInHierarchy("org.hibernate.annotations.OptimisticLocking") != null)) {
            Annotation optimisticLocking = entity.annotationInHierarchy("org.hibernate.annotations.OptimisticLocking");
            String type = entity.annotationValueName(optimisticLocking, "type");
            if (("DIRTY".equals(type) || "ALL".equals(type))
                    && entity.annotationInHierarchy("org.hibernate.annotations.DynamicUpdate") == null) {
                details.add(entity.name() + " uses @OptimisticLocking(" + type + ") without @DynamicUpdate.");
            }
        }
        return violation(details);
    }
}

final class LobLazyFetchRule extends AbstractHibernateRule {

    LobLazyFetchRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-FETCH-005",
                        "Enhanced @Lob attributes should be loaded lazily",
                        HibernateCategory.FETCHING,
                        "MEDIUM",
                        "Detects @Lob attributes that remain eager on entities where Hibernate bytecode enhancement"
                                + " can honor @Basic(fetch=LAZY).",
                        "On bytecode-enhanced entities, annotate infrequently accessed @Lob attributes with"
                                + " @Basic(fetch = FetchType.LAZY). Without enhancement, do not add the annotation:"
                                + " Hibernate cannot defer the column load.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching-basics-lazy"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute :
                    context.targets(entity.attributes(), HibernateAttributeModel::isLob)) {
                if (attribute.isLob() && !attribute.hasBasicLazy() && context.isHibernateEnhancementEnabled(entity)) {
                    details.add(attribute.description()
                            + " is annotated with @Lob but does not declare @Basic(fetch = LAZY).");
                }
            }
        }
        return violation(details);
    }
}

final class LazyBasicWithoutEnhancementRule extends AbstractHibernateRule {

    LazyBasicWithoutEnhancementRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-FETCH-007",
                        "Lazy basic attributes require bytecode enhancement",
                        HibernateCategory.FETCHING,
                        "MEDIUM",
                        "Detects @Basic(fetch=LAZY) attributes on entities where Hibernate bytecode enhancement is not"
                                + " available.",
                        "Enable Hibernate bytecode enhancement so lazy basic attributes can defer their columns, or"
                                + " remove the ineffective LAZY declaration.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching-basics-lazy"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.targets(
                context.entities(),
                candidate -> candidate.attributes().stream().anyMatch(HibernateAttributeModel::hasBasicLazy))) {
            if (context.isHibernateEnhancementEnabled(entity)) {
                continue;
            }
            for (HibernateAttributeModel attribute : entity.attributes()) {
                if (attribute.hasBasicLazy()) {
                    details.add(attribute.description()
                            + " declares @Basic(fetch = LAZY), but the entity is not bytecode enhanced.");
                }
            }
        }
        return violation(details);
    }
}

final class CollectionFetchJoinAnnotationRule extends AbstractHibernateRule {

    CollectionFetchJoinAnnotationRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-FETCH-006",
                        "Collection associations should not declare @Fetch(JOIN)",
                        HibernateCategory.FETCHING,
                        "MEDIUM",
                        "Reviews collection @Fetch(JOIN) declarations. Mapping fetch mode does not govern every JPQL"
                                + " query fetch plan and does not prove an unbounded result.",
                        "Prefer @Fetch(FetchMode.SELECT), explicit entity queries, or DTO projections, and request"
                                + " JOIN FETCH only on the specific query that needs the graph.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching-strategies"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute :
                    context.targets(entity.attributes(), HibernateAttributeModel::isCollectionAssociation)) {
                if (!attribute.isCollectionAssociation()) {
                    continue;
                }
                Annotation fetch = attribute.fetchAnnotation();
                if (fetch != null && "JOIN".equals(attribute.annotationValueName(fetch, "value"))) {
                    details.add(attribute.description() + " is a collection mapped with @Fetch(FetchMode.JOIN).");
                }
            }
        }
        return violation(details);
    }
}

final class SubselectCollectionFetchRule extends AbstractHibernateRule {

    SubselectCollectionFetchRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-FETCH-008",
                        "Subselect collection fetching should be reviewed",
                        HibernateCategory.FETCHING,
                        "INFO",
                        "Reviews collection @Fetch(SUBSELECT) declarations, which may initialize the role for an"
                                + " applicable owner-loading group, not every owner in the persistence context.",
                        "Use SUBSELECT only for bounded owner sets. Prefer an explicit entity query or DTO projection"
                                + " when the collection can be large or when only a subset is needed.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching-fetchmode-subselect"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute :
                    context.targets(entity.attributes(), HibernateAttributeModel::isCollectionAssociation)) {
                if (!attribute.isCollectionAssociation()) {
                    continue;
                }
                Annotation fetch = attribute.fetchAnnotation();
                if (fetch != null && "SUBSELECT".equals(attribute.annotationValueName(fetch, "value"))) {
                    details.add(attribute.description()
                            + " uses @Fetch(FetchMode.SUBSELECT); verify that owner and collection"
                            + " cardinalities stay bounded.");
                }
            }
        }
        return violation(details);
    }
}

final class GeneratedValueWithoutStrategyRule extends AbstractHibernateRule {

    GeneratedValueWithoutStrategyRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-ID-004",
                        "Review provider-selected identifier strategies",
                        HibernateCategory.IDENTIFIERS,
                        "INFO",
                        "AUTO is a supported strategy. This review observes the declaration, not the selected"
                                + " generator, optimizer or database schema; UUID identifiers and framework-declared"
                                + " Panache identifiers are excluded.",
                        "Keep AUTO when provider selection fits the database and workload. Choose an explicit strategy"
                                + " only after reviewing the effective generator and portability requirements.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#identifiers-generators-auto"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : context.targets(
                    entity.attributes(),
                    candidate -> candidate.hasGeneratedValue()
                            && !HibernateRuleModelSupport.isFrameworkDeclaredPanacheIdentifier(candidate)
                            && !candidate.isUuidType())) {
                Annotation generated = attribute.generatedValueAnnotation();
                if (generated == null) {
                    continue;
                }
                // Panache's own PanacheEntity declares "@Id @GeneratedValue public Long id" with no explicit
                // strategy; the application inherits it as-is and cannot annotate it, so it is not a real finding.
                if (HibernateRuleModelSupport.isFrameworkDeclaredPanacheIdentifier(attribute)) {
                    continue;
                }
                // AUTO on a UUID-typed identifier resolves to Hibernate's UuidGenerator, not a sequence; that
                // case - and its own remediation - belongs exclusively to HIB-ID-005, so double-reporting the
                // same attribute here (with a rationale that is factually wrong for UUID ids) is avoided.
                if (attribute.isUuidType()) {
                    continue;
                }
                String strategy = attribute.annotationValueName(generated, "strategy");
                if (strategy == null || "AUTO".equals(strategy)) {
                    details.add(attribute.description() + " uses @GeneratedValue without an explicit strategy.");
                }
            }
        }
        return violation(details);
    }
}

final class UuidIdentifierGeneratorRule extends AbstractHibernateRule {

    UuidIdentifierGeneratorRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-ID-005",
                        "Review generated UUID strategy when index locality matters",
                        HibernateCategory.IDENTIFIERS,
                        "LOW",
                        "Detects UUID identifiers that rely on @GeneratedValue without the Hibernate @UuidGenerator"
                                + " strategy.",
                        "JPA-generated UUIDs are supported. Review the selected generator and benchmark index locality"
                                + " only when relevant; a proprietary annotation is not required and its presence does not"
                                + " prove ordering.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#identifiers-generators-uuid"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute :
                    context.targets(entity.attributes(), candidate -> candidate.hasId() && candidate.isUuidType())) {
                if (!attribute.hasId() || !attribute.isUuidType()) {
                    continue;
                }
                if (attribute.hasGeneratedValue() && !attribute.hasUuidGenerator()) {
                    details.add(attribute.description()
                            + " uses a supported generated UUID without a declared Hibernate generator style;"
                            + " effective style and index locality are not observed.");
                }
            }
        }
        return violation(details);
    }
}

final class ElementCollectionListOrderRule extends AbstractHibernateRule {

    ElementCollectionListOrderRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-MAP-010",
                        "Review element-collection list ordering and mutation cost",
                        HibernateCategory.MAPPING,
                        "MEDIUM",
                        "Observes @ElementCollection List without an explicit order column. Effective list"
                                + " classification and actual collection mutation SQL are not inspected.",
                        "Choose list, ordered-list or set semantics from domain requirements and measure"
                                + " representative mutations. Do not add competing ordering annotations merely to satisfy"
                                + " a check.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#collections-list"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute :
                    context.targets(entity.attributes(), HibernateAttributeModel::isElementCollection)) {
                if (attribute.isElementCollection() && attribute.isListAttribute() && !attribute.hasOrderColumn()) {
                    details.add(attribute.description() + " is an @ElementCollection List without @OrderColumn"
                            + (attribute.hasOrderBy()
                                    ? " (its @OrderBy only affects read-time ordering and does not fix this)."
                                    : "."));
                }
            }
        }
        return violation(details);
    }
}

final class FinalEntityRule extends AbstractHibernateRule {

    FinalEntityRule() {
        super(new HibernateRuleDefinition(
                "HIB-MAP-011",
                "Entity classes should not be final",
                HibernateCategory.MAPPING,
                "INFO",
                "Detects final entity classes that are not proven bytecode-enhanced. Final entities are not"
                        + " Jakarta-portable and cannot use subclass proxies for lazy to-one associations.",
                "Use non-final types when subclass proxies are needed. Verify emitted Kotlin classes: the JPA plugin"
                        + " enables no-arg and all-open together starting at 2.3.20; older versions require separate"
                        + " all-open setup. Enhancement is checked independently.",
                "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#entity"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.targets(context.entities())) {
            if (entity.isFinalClass() && !context.isHibernateEnhancementEnabled(entity)) {
                details.add(entity.name()
                        + " is declared final and is not proven bytecode-enhanced, so Hibernate cannot create"
                        + " subclass proxies for lazy to-one associations.");
            }
        }
        return violation(details);
    }
}

final class SingleTableMissingDiscriminatorRule extends AbstractHibernateRule {

    SingleTableMissingDiscriminatorRule() {
        super(new HibernateRuleDefinition(
                "HIB-MAP-012",
                "SINGLE_TABLE inheritance should declare @DiscriminatorColumn",
                HibernateCategory.MAPPING,
                "INFO",
                "Detects @Inheritance(SINGLE_TABLE) roots without an explicit @DiscriminatorColumn, leaving the"
                        + " default name and length implicit.",
                "Declare @DiscriminatorColumn (with name, type, and length) on the SINGLE_TABLE root so schema"
                        + " generation and reviews see the chosen contract instead of provider defaults.",
                "https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1.html#a3158"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.targets(
                context.entities(), candidate -> "SINGLE_TABLE".equals(candidate.inheritanceStrategy()))) {
            if ("SINGLE_TABLE".equals(entity.inheritanceStrategy()) && !entity.hasDiscriminatorColumn()) {
                details.add(entity.name() + " uses SINGLE_TABLE inheritance without @DiscriminatorColumn.");
            }
        }
        return violation(details);
    }
}

final class StringColumnLengthRule extends AbstractHibernateRule {

    StringColumnLengthRule() {
        super(new HibernateRuleDefinition(
                "HIB-MAP-013",
                "String columns should declare explicit length",
                HibernateCategory.MAPPING,
                "INFO",
                "Observes String attributes without length metadata on the inspected member. Effective schema length,"
                        + " validation, converters and XML overrides are not verified.",
                "Review domain constraints and actual schema ownership before specifying column length; an annotation"
                        + " is not application validation.",
                "https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1.html#a2128"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : context.targets(
                    entity.attributes(),
                    candidate -> candidate.isStringType() && !candidate.hasId() && !candidate.isLob())) {
                if (!attribute.isStringType() || attribute.hasId() || attribute.isLob()) {
                    continue;
                }
                Annotation column = attribute.columnAnnotation();
                Integer length = column == null ? null : attribute.annotationIntValue(column, "length");
                String columnDefinition =
                        column == null ? null : attribute.annotationStringValue(column, "columnDefinition");
                if (length == null && (columnDefinition == null || columnDefinition.isBlank())) {
                    details.add(attribute.description() + " is a String column without an explicit length.");
                }
            }
        }
        return violation(details);
    }
}

final class BigDecimalPrecisionRule extends AbstractHibernateRule {

    BigDecimalPrecisionRule() {
        super(new HibernateRuleDefinition(
                "HIB-MAP-014",
                "BigDecimal columns should declare precision and scale",
                HibernateCategory.MAPPING,
                "MEDIUM",
                "Detects BigDecimal attributes without @Column(precision=..., scale=...), which falls back to provider"
                        + " defaults that vary by database.",
                "Review domain precision and actual schema mapping. Zero/default precision can be intentional; mapping"
                        + " annotations neither validate values nor define application rounding.",
                "https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1.html#a2128"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute :
                    context.targets(entity.attributes(), HibernateAttributeModel::isBigDecimalType)) {
                if (!attribute.isBigDecimalType()) {
                    continue;
                }
                Annotation column = attribute.columnAnnotation();
                Integer precision = column == null ? null : attribute.annotationIntValue(column, "precision");
                Integer scale = column == null ? null : attribute.annotationIntValue(column, "scale");
                if (precision == null || precision == 0 || scale == null) {
                    details.add(
                            attribute.description() + " is a BigDecimal column without explicit precision and scale.");
                }
            }
        }
        return violation(details);
    }
}

final class LegacyDateTimeRule extends AbstractHibernateRule {

    LegacyDateTimeRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-MAP-015",
                        "Date/time attributes should use java.time",
                        HibernateCategory.MAPPING,
                        "LOW",
                        "Detects persistent attributes typed as java.util.Date, java.util.Calendar, or java.sql"
                                + " temporal types instead of java.time.",
                        "Choose a semantically equivalent java.time type while preserving date, instant and zone"
                                + " requirements. Not every java.time type is zone-aware; @Temporal is deprecated in"
                                + " Persistence 3.2.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#basic-mapping-temporal"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : context.targets(entity.attributes())) {
                if (attribute.isLegacyTemporalType()) {
                    details.add(attribute.description() + " uses legacy temporal type "
                            + attribute.rawType().getName() + "; prefer a java.time type.");
                }
            }
        }
        return violation(details);
    }
}

final class ManyToOneOptionalRule extends AbstractHibernateRule {

    ManyToOneOptionalRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-MAP-016",
                        "@ManyToOne should set optional=false when the join column is non-nullable",
                        HibernateCategory.MAPPING,
                        "LOW",
                        "Detects @ManyToOne associations whose @JoinColumn is non-nullable but whose mapping still"
                                + " allows optional=true (the default).",
                        "Align association optionality with the intended foreign-key nullability. This annotation"
                                + " consistency check does not establish secondary-select or proxy behavior.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#associations-many-to-one"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute :
                    context.targets(entity.attributes(), candidate -> candidate.manyToOneAnnotation() != null)) {
                Annotation manyToOne = attribute.manyToOneAnnotation();
                if (manyToOne == null) {
                    continue;
                }
                Boolean optional = attribute.annotationBooleanValue(manyToOne, "optional");
                if (Boolean.FALSE.equals(optional)) {
                    continue;
                }
                Annotation joinColumn = attribute.joinColumnAnnotation();
                if (joinColumn == null) {
                    continue;
                }
                Boolean nullable = attribute.annotationBooleanValue(joinColumn, "nullable");
                if (Boolean.FALSE.equals(nullable)) {
                    details.add(attribute.description()
                            + " is @ManyToOne with @JoinColumn(nullable=false) but optional=true; set optional=false.");
                }
            }
        }
        return violation(details);
    }
}

final class EqualsHashCodeAssociationsRule extends AbstractHibernateRule {

    EqualsHashCodeAssociationsRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-ENTITY-003",
                        "equals/hashCode should not include lazy associations",
                        HibernateCategory.ENTITY_DESIGN,
                        "INFO",
                        "Detects entities that override equals and hashCode while exposing JPA associations. Generated"
                                + " implementations (Lombok @Data/@EqualsAndHashCode without exclusions, IDE templates)"
                                + " typically include those associations and trigger lazy loads when entities are stored"
                                + " in collections.",
                        "Base equals/hashCode on a stable business key or natural id only. If associations must"
                                + " participate, exclude lazy ones explicitly and use the entity class to avoid proxy"
                                + " mismatches.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#mapping-model-pojo-equalshashcode"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.targets(
                context.entities(),
                candidate -> candidate.attributes().stream().anyMatch(HibernateAttributeModel::isAssociation))) {
            if (!entity.overridesEquals() || !entity.overridesHashCode()) {
                continue;
            }
            boolean hasAssociation = entity.attributes().stream().anyMatch(HibernateAttributeModel::isAssociation);
            if (hasAssociation) {
                details.add(entity.name()
                        + " overrides equals/hashCode and declares associations; verify they are not included.");
            }
        }
        return violation(details);
    }
}

final class ToStringAssociationsRule extends AbstractHibernateRule {

    ToStringAssociationsRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-ENTITY-004",
                        "toString should not include lazy associations",
                        HibernateCategory.ENTITY_DESIGN,
                        "INFO",
                        "Detects entities that override toString while exposing JPA associations. Generated"
                                + " implementations (Lombok @Data/@ToString without exclusions, IDE templates) typically"
                                + " traverse associations and trigger N+1 lazy loads or LazyInitializationException"
                                + " outside an open session.",
                        "Base toString on the identifier and a few stable scalar fields. Exclude associations"
                                + " explicitly (for example with @ToString(exclude=...)) so logging or debugging does not"
                                + " pull the object graph.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#mapping-model-pojo-tostring"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.targets(
                context.entities(),
                candidate -> candidate.attributes().stream().anyMatch(HibernateAttributeModel::isAssociation))) {
            if (!entity.overridesToString()) {
                continue;
            }
            boolean hasAssociation = entity.attributes().stream().anyMatch(HibernateAttributeModel::isAssociation);
            if (hasAssociation) {
                details.add(
                        entity.name() + " overrides toString and declares associations; verify they are not included.");
            }
        }
        return violation(details);
    }
}

final class PublicPersistentFieldRule extends AbstractHibernateRule {

    PublicPersistentFieldRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-ENTITY-005",
                        "Persistent fields should not be public",
                        HibernateCategory.ENTITY_DESIGN,
                        "LOW",
                        "Reviews public mapped fields as an encapsulation choice, not proof of broken dirty checking."
                                + " Verified Panache transformations and Kotlin accessor-backed fields are excluded.",
                        "Prefer controlled access where it protects invariants; preserve framework-supported field"
                                + " access and verify actual enhancement behavior.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#entity-pojo-accessors"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.observed()
                ? context.applicationFacts().panacheEnhancementVerified()
                : HibernateRuleModelSupport.isPanacheFieldAccessRewriteActive()) {
            return pass();
        }
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : context.targets(
                    entity.attributes(), candidate -> candidate.fieldMember() && !candidate.isTransient())) {
                // fieldMember (not the "()"-suffixed name heuristic) is what actually distinguishes field
                // access from property (getter) access; property-access entities resolve their attributes
                // from a public getter Method, which is fully JPA/Hibernate-instrumented and not a finding.
                if (attribute.publicMember()
                        && attribute.fieldMember()
                        && !attribute.isTransient()
                        && !isKotlinPropertyBackingField(entity, attribute)) {
                    details.add(attribute.description() + " is exposed as a public field.");
                }
            }
        }
        return violation(details);
    }

    /**
     * Whether this attribute is the backing field of a Kotlin property, which the compiler must leave
     * public even though the author declared no public field.
     *
     * <p>A {@code lateinit var} is reached through its generated accessors from every language that
     * consumes it, so Hibernate's instrumentation is intact and there is nothing here to encapsulate.
     * A field the author did expose, written {@code @JvmField var}, has no accessors and stays reported.
     */
    private static boolean isKotlinPropertyBackingField(
            HibernateEntityModel entity, HibernateAttributeModel attribute) {
        return KotlinReflection.isAccessorBackedProperty(declaringClass(entity, attribute), attribute.name());
    }

    /**
     * The class that declares this attribute, found by walking the entity hierarchy for the name the
     * attribute records. Null when the model carries no Java type, as hand-built models in tests do.
     */
    private static Class<?> declaringClass(HibernateEntityModel entity, HibernateAttributeModel attribute) {
        for (Class<?> current = entity.javaType(); current != null && current != Object.class; ) {
            if (current.getName().equals(attribute.entityName())) {
                return current;
            }
            current = current.getSuperclass();
        }
        return null;
    }
}

final class ModifyingClearAutomaticallyRule extends AbstractHibernateRule {

    ModifyingClearAutomaticallyRule() {
        super(new HibernateRuleDefinition(
                "HIB-QUERY-001",
                "@Modifying bulk queries should clear stale persistence context",
                HibernateCategory.QUERY,
                "INFO",
                "Detects Spring Data @Modifying queries that do not set clearAutomatically, so the persistence context"
                        + " can hold stale entities after the bulk update or delete. flushAutomatically synchronizes"
                        + " pending changes before the query but does not clear those stale entities afterward.",
                "Review flush/clear boundaries or isolate bulk work in its own persistence context. Clearing can"
                        + " discard unflushed state; automatic flags are not universally required.",
                "https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.modifying-queries"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.repositories().isEmpty()) {
            return skipped("No repository metadata was detected.");
        }
        List<String> details = new ArrayList<>();
        for (HibernateRepositoryModel repository : context.repositories()) {
            for (HibernateRepositoryMethodModel method : repository.methods()) {
                if (!method.modifying()
                        || !HibernateQueryShape.bulk(method.query())
                        || context.observed()
                                && (!method.evidence().verifiedQueryMethod()
                                        || method.evidence().queryRewriter())) {
                    continue;
                }
                context.evidence().markApplicable(true);
                if (!method.modifyingClearsAutomatically()) {
                    String flushDetail = method.modifyingFlushesAutomatically()
                            ? " flushAutomatically=true does not clear stale managed entities."
                            : "";
                    details.add(method.description() + " is @Modifying without clearAutomatically." + flushDetail);
                }
            }
        }
        return violation(details);
    }
}

final class StreamReturningMethodRule extends AbstractHibernateRule {

    StreamReturningMethodRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-QUERY-002",
                        "Review streaming query resource lifetime",
                        HibernateCategory.QUERY,
                        "INFO",
                        "Detects Spring Data repository methods that return java.util.stream.Stream. They keep the"
                                + " underlying JDBC cursor open and must run inside an open transaction with the caller"
                                + " closing the stream.",
                        "Keep the required transaction open while consuming and closing streams, usually with"
                                + " try-with-resources. Read-only is optional; this declaration does not prove caller"
                                + " misuse.",
                        "https://docs.spring.io/spring-data/jpa/reference/repositories/query-methods-details.html#repositories.query-streaming"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.repositories().isEmpty()) {
            return skipped("No repository metadata was detected.");
        }
        List<String> details = new ArrayList<>();
        for (HibernateRepositoryModel repository : context.repositories()) {
            for (HibernateRepositoryMethodModel method : context.targets(
                    repository.methods(),
                    candidate -> !context.observed() || candidate.evidence().verifiedQueryMethod())) {
                if (method.returnsStream()
                        && (!context.observed() || method.evidence().verifiedQueryMethod())) {
                    details.add(method.description() + " returns Stream; confirm callers run it inside a transaction.");
                }
            }
        }
        return violation(details);
    }
}

final class NativePagedQueryCountRule extends AbstractHibernateRule {

    NativePagedQueryCountRule() {
        super(new HibernateRuleDefinition(
                "HIB-QUERY-003",
                "Native Page queries should review count derivation",
                HibernateCategory.QUERY,
                "INFO",
                "Detects native @Query methods returning Page without an explicit countQuery. Spring Data can derive"
                        + " counts for some simple native SQL, but complex queries may require an explicit count query or"
                        + " JSqlParser.",
                "Review the generated count query. Add countQuery=... when Spring Data cannot derive a correct count,"
                        + " especially for complex native SQL.",
                "https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.query-methods.at-query"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.repositories().isEmpty()) {
            return skipped("No repository metadata was detected.");
        }
        List<String> details = new ArrayList<>();
        for (HibernateRepositoryModel repository : context.repositories()) {
            for (HibernateRepositoryMethodModel method : repository.methods()) {
                if (!method.nativeQuery()
                        || !method.hasQuery()
                        || method.evidence().namedQuery()
                        || method.evidence().queryRewriter()) {
                    continue;
                }
                if (!method.returnsPage()) {
                    continue;
                }
                context.evidence().markApplicable(true);
                if (!method.hasCountQuery()) {
                    details.add(method.description() + " is a native paged @Query without countQuery.");
                }
            }
        }
        return violation(details);
    }
}

final class DerivedDeleteByQueryRule extends AbstractHibernateRule {

    DerivedDeleteByQueryRule() {
        super(new HibernateRuleDefinition(
                "HIB-QUERY-004",
                "Derived deleteBy methods load entities before deletion",
                HibernateCategory.QUERY,
                "MEDIUM",
                "Detects derived deleteBy.../removeBy... repository methods. Spring Data implements them by selecting"
                        + " matching entities first and then deleting them one by one, which is expensive on large result"
                        + " sets.",
                "Review cardinality before replacing entity-by-entity deletion. Bulk queries change lifecycle"
                        + " callbacks and cascade semantics and require deliberate persistence-context handling.",
                "https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.query-methods.modifying"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.repositories().isEmpty()) {
            return skipped("No repository metadata was detected.");
        }
        List<String> details = new ArrayList<>();
        for (HibernateRepositoryModel repository : context.repositories()) {
            for (HibernateRepositoryMethodModel method : repository.methods()) {
                if (method.isDerivedDeleteMethod() && !method.hasQuery()) {
                    context.evidence().markApplicable(true);
                    if (context.observed() && !method.evidence().derivedQueryVerified()) {
                        context.missingEvidence();
                        continue;
                    }
                    details.add(method.description()
                            + " is a derived delete query; consider an explicit @Modifying bulk delete.");
                }
            }
        }
        return violation(details);
    }
}

final class BulkUpdateVersionRule extends AbstractHibernateRule {

    BulkUpdateVersionRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-QUERY-008",
                        "Bulk updates leave @Version unchanged",
                        HibernateCategory.QUERY,
                        "MEDIUM",
                        "Detects JPQL/HQL bulk UPDATE queries targeting versioned entities that neither use UPDATE VERSIONED"
                                + " nor use a recognized explicit version assignment. Bulk updates leave the database version"
                                + " unchanged by default.",
                        "Review whether this operation should invalidate previously loaded entity versions. Where appropriate,"
                                + " use ordinary managed-entity updates, Hibernate's update versioned syntax, or explicitly maintain"
                                + " the version with a numeric increment, a type-compatible CURRENT_TIMESTAMP assignment, or a"
                                + " fresh version parameter. Parameter values, type compatibility, and actual version changes"
                                + " are not verified.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#batch-bulk-hql-update-delete"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.repositories().isEmpty()) {
            return skipped("No repository metadata was detected.");
        }
        List<String> details = new ArrayList<>();
        for (HibernateRepositoryModel repository : context.repositories()) {
            for (HibernateRepositoryMethodModel method : repository.methods()) {
                if (!method.modifying()
                        || method.nativeQuery()
                        || !HibernateQueryShape.isUpdate(method.query())
                        || context.observed()
                                && (!method.evidence().verifiedQueryMethod()
                                        || method.evidence().queryRewriter())) {
                    continue;
                }
                HibernateQueryShape.UpdateTarget target = HibernateQueryShape.resolveUpdateTarget(context, method);
                if (target == null) {
                    continue;
                }
                HibernateEntityModel entity = target.entity();
                if (!entity.hasVersionAttribute()) {
                    continue;
                }
                context.evidence().markApplicable(true);
                if (target.versioned()) {
                    continue;
                }
                HibernateAttributeModel versionAttr = entity.attributes().stream()
                        .filter(HibernateAttributeModel::hasVersion)
                        .findFirst()
                        .orElse(null);
                if (versionAttr != null
                        && (HibernateQueryShape.maintainsVersion(
                                        target.query(), target.alias(), versionAttr.propertyName())
                                || HibernateQueryShape.maintainsVersion(
                                        target.query(), target.alias(), versionAttr.name()))) {
                    continue;
                }
                details.add(method.description() + " performs a bulk UPDATE on versioned entity " + entity.name()
                        + " without advancing its version attribute.");
            }
        }
        return violation(details);
    }
}

final class SqlLoggingInProductionRule extends AbstractHibernateRule {

    @Override
    public boolean applicationWide() {
        return true;
    }

    SqlLoggingInProductionRule() {
        super(new HibernateRuleDefinition(
                "HIB-CONFIG-012",
                "SQL logging should be off when a production profile is active",
                HibernateCategory.CONFIGURATION,
                "MEDIUM",
                "Detects show-sql or DEBUG/TRACE logging for Hibernate SQL/binder categories while a production-like"
                        + " profile (prod, production, staging) is active.",
                "Review statement and parameter logging exposure in production-like profiles; performance cost depends"
                        + " on workload and logging configuration and is not measured here.",
                "https://docs.spring.io/spring-boot/reference/features/logging.html"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (!context.isProductionProfileActive()) {
            return pass();
        }
        if (context.observed()
                ? context.required(context.applicationFacts().sqlLoggerEnabled())
                        || context.required(context.applicationFacts().bindLoggerEnabled())
                : context.isSqlLoggingEnabled()) {
            return violation(List.of("SQL logging is enabled while a production profile is active."));
        }
        return pass();
    }
}

final class JdbcTimeZoneRule extends AbstractHibernateRule {

    JdbcTimeZoneRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CONFIG-013",
                        "Review applicable JDBC temporal binding",
                        HibernateCategory.CONFIGURATION,
                        "LOW",
                        "Reviews a known absent JDBC time-zone setting only when temporal mappings exist. Driver,"
                                + " converter and per-query binding semantics remain unobserved.",
                        "Choose time-zone handling consistent with the mapped temporal types and database; do not"
                                + " prescribe UTC without reviewing application semantics.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#basic-datetime-timezone"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.entities().stream()
                .flatMap(entity -> entity.attributes().stream())
                .noneMatch(attribute -> attribute.rawType() != null
                        && (attribute.rawType().getName().startsWith("java.time.")
                                || java.util.Date.class.isAssignableFrom(attribute.rawType())))) {
            return skipped("No temporal mappings were observed.");
        }
        String value =
                context.firstProperty("spring.jpa.properties.hibernate.jdbc.time_zone", "hibernate.jdbc.time_zone");
        if (value == null || value.isBlank()) {
            return violation(List.of("hibernate.jdbc.time_zone is not configured."));
        }
        return pass();
    }
}

final class HibernateBuiltinPoolRule extends AbstractHibernateRule {

    HibernateBuiltinPoolRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CONFIG-014",
                        "Hibernate's built-in connection pool should not be used",
                        HibernateCategory.CONFIGURATION,
                        "HIGH",
                        "Checks the selected connection provider, not pool_size property presence, for Hibernate's"
                                + " built-in testing pool.",
                        "Use a managed, monitored connection pool for shared workloads; stale pool_size properties do"
                                + " not establish which provider is active.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#database-connectionprovider"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        context.evidence().markApplicable(!context.entities().isEmpty());
        if (!context.observed()
                || context.factorySettings().connectionProvider()
                        == HibernateFactorySettings.ConnectionProvider.UNKNOWN) {
            context.missingEvidence();
            return skipped("Selected connection provider is unknown; pool_size does not establish activation.");
        }
        if (context.factorySettings().connectionProvider() == HibernateFactorySettings.ConnectionProvider.BUILT_IN)
            return violation(
                    List.of("The selected provider is Hibernate's built-in connection pool; use a managed pool for"
                            + " shared workloads."));
        return pass();
    }
}

final class DeferDatasourceInitializationRule extends AbstractHibernateRule {

    @Override
    public boolean applicationWide() {
        return true;
    }

    DeferDatasourceInitializationRule() {
        super(new HibernateRuleDefinition(
                "HIB-CONFIG-015",
                "Deferred script initialization should have an intentional order",
                HibernateCategory.CONFIGURATION,
                "INFO",
                "Detects spring.jpa.defer-datasource-initialization=true, which moves script-based datasource"
                        + " initialization until after JPA initialization.",
                "Verify that script-based initialization has the intended owner and order. This setting is valid with"
                        + " schema validation or externally managed schemas when scripts intentionally seed an existing"
                        + " schema.",
                "https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.datasource.initialization"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (!context.isPropertyTrue("spring.jpa.defer-datasource-initialization")) {
            return pass();
        }
        String detail = "spring.jpa.defer-datasource-initialization=true defers script-based datasource initialization"
                + " until after JPA initialization"
                + "."
                + " Verify that this ordering is intentional.";
        return violation(HibernateRuleSupport.INFO, detail);
    }
}

final class CacheAssociationCoverageRule extends AbstractHibernateRule {

    CacheAssociationCoverageRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CACHE-001",
                        "Cached entity association coverage should be reviewed",
                        HibernateCategory.CACHING,
                        "INFO",
                        "Detects cached entities whose associations target uncached entities. Association coverage is"
                                + " a workload-specific second-level-cache optimization, not a correctness requirement.",
                        "Measure cache hit rates and access patterns before caching associated entities or collection"
                                + " roles. Leave mutable or low-hit targets uncached when that better fits the workload.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#caching-entity"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.observed() && !context.required(context.factorySettings().secondLevelCache()))
            return skipped("Unit second-level cache is disabled.");
        if (context.entities().isEmpty()) {
            return pass();
        }
        Map<String, HibernateEntityModel> byJavaType = HibernateRuleModelSupport.entitiesByJavaType(context.entities());
        context.evidence().markApplicable(false);
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            if (!entity.isJpaCacheable() && !entity.hasHibernateCacheAnnotation()) {
                continue;
            }
            for (HibernateAttributeModel attribute :
                    context.targets(entity.attributes(), HibernateAttributeModel::isAssociation)) {
                if (!attribute.isAssociation()) {
                    continue;
                }
                Class<?> targetType = HibernateRuleModelSupport.associationTargetType(attribute);
                if (targetType == null) {
                    continue;
                }
                HibernateEntityModel target = byJavaType.get(targetType.getName());
                if (target == null) {
                    continue;
                }
                if (!target.isJpaCacheable() && !target.hasHibernateCacheAnnotation()) {
                    details.add(attribute.description() + " references uncached entity " + target.name() + ".");
                }
            }
        }
        return violation(details);
    }
}

final class ReadOnlyCacheOnWritableEntityRule extends AbstractHibernateRule {

    ReadOnlyCacheOnWritableEntityRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CACHE-002",
                        "READ_ONLY cache strategy on writable entities is unsafe",
                        HibernateCategory.CACHING,
                        "MEDIUM",
                        "Reviews READ_ONLY cache declarations with update-related annotations only while unit caching"
                                + " is enabled. @Immutable versioned entities are exempt; actual writes are not observed.",
                        "Confirm intended mutability and provider behavior before choosing a strategy. Cache"
                                + " strategies do not automatically detect external database writers.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#caching-entity-cache-mapping"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.observed() && !context.required(context.factorySettings().secondLevelCache()))
            return skipped("Unit second-level cache is disabled.");
        List<String> details = new ArrayList<>();
        context.evidence().markApplicable(false);
        for (HibernateEntityModel entity :
                context.targets(context.entities(), candidate -> candidate.hibernateCacheUsageName() != null)) {
            String usage = entity.hibernateCacheUsageName();
            if (!"READ_ONLY".equals(usage) || entity.isImmutable()) {
                continue;
            }
            if (entity.hasVersionAttribute() || entity.hasDynamicUpdate()) {
                details.add(entity.name()
                        + " uses @Cache(usage=READ_ONLY) but appears to be writable (@Version or"
                        + " @DynamicUpdate present).");
            }
        }
        return violation(details);
    }
}

final class ImmutableEntityCacheStrategyRule extends AbstractHibernateRule {

    ImmutableEntityCacheStrategyRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CACHE-003",
                        "Immutable cached entities should use READ_ONLY",
                        HibernateCategory.CACHING,
                        "INFO",
                        "Detects @Immutable entities using a mutable second-level cache concurrency strategy.",
                        "Consider READ_ONLY when it simplifies intentionally immutable cached data; do not remove"
                                + " @Immutable solely to satisfy an advisory.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#caching-entity-cache-mapping"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.observed() && !context.required(context.factorySettings().secondLevelCache()))
            return skipped("Unit second-level cache is disabled.");
        List<String> details = new ArrayList<>();
        context.evidence().markApplicable(false);
        for (HibernateEntityModel entity : context.targets(
                context.entities(),
                candidate -> candidate.isImmutable() && candidate.hibernateCacheUsageName() != null)) {
            String usage = entity.hibernateCacheUsageName();
            if (entity.isImmutable() && usage != null && !"READ_ONLY".equals(usage) && !"NONE".equals(usage)) {
                details.add(entity.name() + " is @Immutable but uses @Cache(usage=" + usage + ").");
            }
        }
        return violation(details);
    }
}

final class FailOnPaginationOverCollectionFetchRule extends AbstractHibernateRule {

    FailOnPaginationOverCollectionFetchRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CONFIG-016",
                        "Fail on pagination over collection fetch",
                        HibernateCategory.CONFIGURATION,
                        "HIGH",
                        "Reviews the observed unit pagination guard and eligible limited collection-fetch queries."
                                + " Neither version nor guard alone certifies SQL pushdown; an explicit Hibernate 7.4"
                                + " limitInMemory hint may override the guard.",
                        "Consider fail-fast collection-fetch pagination where supported, inspect query-specific hints,"
                                + " and page root IDs first when SQL-side limiting is unavailable.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#collections-fetching"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> riskyQueries = HibernateRuleModelSupport.paginatedCollectionFetchFindings(context);
        if (context.hasHibernateCollectionFetchPaginationFix()) {
            if (!riskyQueries.isEmpty()) return violation(HibernateRuleSupport.HIGH, riskyQueries);
            return skipped("SQL-side pagination and runtime hints are not proven by the Hibernate version.");
        }
        if (context.hibernateVersion().major() == null) {
            context.missingEvidence();
            return skipped("Owning unit Hibernate version is unknown.");
        }
        if (context.isPropertyTrue(
                "spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch",
                "hibernate.query.fail_on_pagination_over_collection_fetch")) {
            return pass();
        }
        List<String> details = new ArrayList<>();
        if (riskyQueries.isEmpty()) {
            details.add("The factory pagination guard is disabled; consider a fail-fast guard for queries that would"
                    + " require in-memory collection-fetch limiting.");
            return violation(HibernateRuleSupport.INFO, details);
        }
        details.addAll(riskyQueries);
        return violation(HibernateRuleSupport.HIGH, details);
    }
}

final class FormatSqlInProductionRule extends AbstractHibernateRule {

    FormatSqlInProductionRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CONFIG-017",
                        "Disable SQL formatting in production",
                        HibernateCategory.CONFIGURATION,
                        "LOW",
                        "Detects hibernate.format_sql=true while SQL logging is enabled in a production profile.",
                        "Disable hibernate.format_sql when verbose SQL logging is enabled in production to avoid"
                                + " formatting every logged statement.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#configurations-logging"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (!context.isProductionProfileActive()) {
            return pass();
        }
        if (context.isPropertyTrue("spring.jpa.properties.hibernate.format_sql", "hibernate.format_sql")
                && context.isStatementLoggingEnabled()) {
            return violation(
                    List.of("hibernate.format_sql and SQL logging are enabled while a production profile is active."));
        }
        return pass();
    }
}

final class BindParameterLoggingInProductionRule extends AbstractHibernateRule {

    @Override
    public boolean applicationWide() {
        return true;
    }

    BindParameterLoggingInProductionRule() {
        super(new HibernateRuleDefinition(
                "HIB-CONFIG-018",
                "Bind-parameter logging should be off in production",
                HibernateCategory.CONFIGURATION,
                "HIGH",
                "Detects TRACE logging for org.hibernate.orm.jdbc.bind (or the legacy"
                        + " org.hibernate.type.descriptor.sql.BasicBinder binder logger, or the Quarkus-native"
                        + " quarkus.hibernate-orm.log.bind-parameters convenience flag) while a production-like profile is"
                        + " active. At TRACE, Hibernate logs every bound parameter value, which can leak PII, credentials,"
                        + " or tokens passed as query parameters into application logs.",
                "Keep bind-parameter logging off in production; only enable it temporarily, in a non-production"
                        + " environment, while diagnosing a specific issue.",
                "https://quarkus.io/guides/hibernate-orm"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (!context.isProductionProfileActive()) {
            return pass();
        }
        if (context.isBindParameterLoggingEnabled()) {
            return violation(
                    List.of("Bind-parameter logging is enabled at TRACE while a production profile is active."));
        }
        return pass();
    }
}

final class SqlCommentsRule extends AbstractHibernateRule {

    SqlCommentsRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CONFIG-019",
                        "SQL comments should be enabled intentionally",
                        HibernateCategory.CONFIGURATION,
                        "INFO",
                        "Reviews enabled SQL comments as an observability trade-off. Stable comments need not increase"
                                + " statement-cache cardinality; actual text variability is not observed.",
                        "Retain SQL comments when their observability benefit justifies any measured overhead. Stable"
                                + " comments do not necessarily increase statement-cache cardinality.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#configurations-logging"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.isPropertyTrue("spring.jpa.properties.hibernate.use_sql_comments", "hibernate.use_sql_comments")) {
            return violation(
                    List.of("hibernate.use_sql_comments is enabled; confirm that statement-cache efficiency and"
                            + " network overhead are acceptable."));
        }
        return pass();
    }
}

final class OracleJdbcFetchSizeRule extends AbstractHibernateRule {

    OracleJdbcFetchSizeRule() {
        super(new HibernateRuleDefinition(
                "HIB-CONFIG-020",
                "Oracle JDBC fetch size should exceed the driver default",
                HibernateCategory.CONFIGURATION,
                "INFO",
                "Detects Oracle-backed persistence units that leave hibernate.jdbc.fetch_size unset or at 10 or less.",
                "For result sets that commonly exceed ten rows, set and measure a bounded hibernate.jdbc.fetch_size"
                        + " above Oracle's default of 10; keep the driver default when queries are consistently small.",
                "https://vladmihalcea.com/resultset-statement-fetching-with-jdbc-and-hibernate/"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (!isOracle(context)) {
            return skipped("The persistence unit is not known to use Oracle.");
        }
        Integer fetchSize = context.firstIntegerProperty(
                "spring.jpa.properties.hibernate.jdbc.fetch_size", "hibernate.jdbc.fetch_size");
        if (fetchSize != null && fetchSize > 10) {
            return pass();
        }
        String configured = fetchSize == null ? "not configured" : "set to " + fetchSize;
        return violation(List.of("Oracle was detected and hibernate.jdbc.fetch_size is " + configured
                + "; the Oracle JDBC driver defaults to fetching 10 rows per roundtrip."));
    }

    private boolean isOracle(HibernateContext context) {
        if (context.observed())
            return context.required(context.factorySettings().oracle());
        String databaseKind = context.firstProperty("quarkus.datasource.db-kind", "spring.jpa.database");
        if ("oracle".equalsIgnoreCase(databaseKind)) {
            return true;
        }
        String url = context.firstProperty(
                "spring.datasource.url", "jakarta.persistence.jdbc.url", "hibernate.connection.url");
        if (url != null && url.toLowerCase(Locale.ROOT).startsWith("jdbc:oracle:")) {
            return true;
        }
        String driver = context.firstProperty(
                "spring.datasource.driver-class-name",
                "jakarta.persistence.jdbc.driver",
                "hibernate.connection.driver_class");
        if (driver != null && driver.toLowerCase(Locale.ROOT).startsWith("oracle.jdbc.")) {
            return true;
        }
        String dialect = context.firstProperty("spring.jpa.database-platform", "hibernate.dialect");
        return dialect != null && dialect.toLowerCase(Locale.ROOT).startsWith("org.hibernate.dialect.oracle");
    }
}

final class NonOwningOneToOneEnhancementRule extends AbstractHibernateRule {

    NonOwningOneToOneEnhancementRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-MAP-018",
                        "Review lazy inverse @OneToOne without enhancement",
                        HibernateCategory.MAPPING,
                        "MEDIUM",
                        "Detects declared lazy inverse one-to-one associations with known absence of enhancement;"
                                + " secondary-load behavior depends on the mapping and query plan.",
                        "Enable bytecode enhancement, or replace the bidirectional @OneToOne with a shared primary key"
                                + " (@MapsId) and a unidirectional mapping.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#BytecodeEnhancement-lazy-loading"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.targets(
                context.entities(),
                candidate -> candidate.attributes().stream()
                        .anyMatch(attribute -> attribute.oneToOneAnnotation() != null))) {
            if (context.isHibernateEnhancementEnabled(entity)) {
                continue;
            }
            for (HibernateAttributeModel attribute : entity.attributes()) {
                java.lang.annotation.Annotation oneToOne = attribute.oneToOneAnnotation();
                if (oneToOne == null) {
                    continue;
                }
                String mappedBy = attribute.annotationStringValue(oneToOne, "mappedBy");
                if (mappedBy != null
                        && !mappedBy.isBlank()
                        && "LAZY".equals(attribute.annotationValueName(oneToOne, "fetch"))) {
                    details.add(attribute.description()
                            + " is a non-owning @OneToOne but bytecode enhancement is disabled.");
                }
            }
        }
        return violation(details);
    }
}

final class MissingForeignKeyIndexRule extends AbstractHibernateRule {

    MissingForeignKeyIndexRule() {
        super(new HibernateRuleDefinition(
                "HIB-MAP-019",
                "Missing foreign key indexes",
                HibernateCategory.MAPPING,
                "INFO",
                "Detects owning foreign key associations whose join column is not the leading column of any @Index"
                        + " declared on the entity's @Table mapping.",
                "Declare the foreign key column as the leading column of an @Index in @Table so the schema generator"
                        + " creates it; if you manage schema with Flyway/Liquibase, make sure the migration creates the"
                        + " index. Unindexed foreign keys slow down joins and parent deletes.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html"));
    }

    // Optional JPA type: compare by class name instead of hard-referencing a class that may be absent at runtime.
    @SuppressWarnings("java:S1872")
    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (!context.managesSchemaIndexes()) {
            return skipped(
                    "Schema indexes are not managed by Hibernate; migration-managed indexes cannot be verified from"
                            + " JPA annotations.");
        }
        List<String> details = new ArrayList<>();
        context.evidence().markApplicable(false);
        List<String> unresolved = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            Set<String> leadingIndexColumns;
            try {
                leadingIndexColumns = leadingIndexColumns(entity.javaType());
            } catch (RuntimeException ex) {
                context.missingEvidence();
                unresolved.add(entity.name() + " (@Table index metadata could not be resolved)");
                continue;
            }
            for (HibernateAttributeModel attribute : context.targets(entity.attributes(), this::isOwningToOne)) {
                if (!isOwningToOne(attribute)) {
                    continue;
                }
                List<String> fkColumns = foreignKeyColumns(attribute);
                if (fkColumns.isEmpty()) {
                    continue;
                }
                boolean anyLeadingIndexed = fkColumns.stream().anyMatch(leadingIndexColumns::contains);
                if (!anyLeadingIndexed) {
                    String reported = fkColumns.get(0);
                    details.add(attribute.description() + " is a foreign key (" + reported
                            + ") with no JPA-declared index leading on that column.");
                }
            }
        }
        if (!details.isEmpty()) {
            return violation(details);
        }
        if (!unresolved.isEmpty()) {
            return skipped("Foreign key index metadata could not be resolved for: " + String.join(", ", unresolved));
        }
        return pass();
    }

    private boolean isOwningToOne(HibernateAttributeModel attribute) {
        if (attribute.hasMapsId()) {
            return false;
        }
        if (attribute.manyToOneAnnotation() != null) {
            return true;
        }
        Annotation oneToOne = attribute.oneToOneAnnotation();
        if (oneToOne == null) {
            return false;
        }
        String mappedBy = attribute.annotationStringValue(oneToOne, "mappedBy");
        return mappedBy == null || mappedBy.isBlank();
    }

    private List<String> foreignKeyColumns(HibernateAttributeModel attribute) {
        List<String> columns = new ArrayList<>();
        Annotation joinColumn = attribute.joinColumnAnnotation();
        if (joinColumn != null) {
            String name = attribute.annotationStringValue(joinColumn, "name");
            if (name != null && !name.isBlank()) {
                columns.add(normalizeIdentifier(name));
            }
            return columns;
        }
        Annotation joinColumns = attribute.annotation("jakarta.persistence.JoinColumns");
        if (joinColumns != null) {
            for (String name : joinColumnsNames(joinColumns)) {
                if (name != null && !name.isBlank()) {
                    columns.add(normalizeIdentifier(name));
                }
            }
            return columns;
        }
        return columns;
    }

    private List<String> joinColumnsNames(Annotation joinColumns) {
        List<String> names = new ArrayList<>();
        try {
            Object value = joinColumns.annotationType().getMethod("value").invoke(joinColumns);
            if (value instanceof Annotation[] entries) {
                for (Annotation entry : entries) {
                    Object name = entry.annotationType().getMethod("name").invoke(entry);
                    names.add(name instanceof String s ? s : null);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Treat an unreadable @JoinColumns as no declared names so the inferred fallback applies.
        }
        return names;
    }

    private Set<String> leadingIndexColumns(Class<?> javaType) {
        Set<String> leading = new HashSet<>();
        Class<?> current = javaType;
        while (current != null && current != Object.class) {
            for (Annotation ann : current.getDeclaredAnnotations()) {
                if (!"jakarta.persistence.Table".equals(ann.annotationType().getName())) {
                    continue;
                }
                try {
                    Annotation[] indexes = (Annotation[])
                            ann.annotationType().getMethod("indexes").invoke(ann);
                    for (Annotation index : indexes) {
                        String columnList = (String)
                                index.annotationType().getMethod("columnList").invoke(index);
                        if (columnList == null || columnList.isBlank()) {
                            continue;
                        }
                        String first = normalizeIndexColumn(columnList.split(",")[0]);
                        if (!first.isEmpty()) {
                            leading.add(first);
                        }
                    }
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }
            current = current.getSuperclass();
        }
        return leading;
    }

    private String normalizeIndexColumn(String column) {
        return normalizeIdentifier(column.replaceFirst("(?i)\\s+(ASC|DESC)\\s*$", ""));
    }

    private String normalizeIdentifier(String identifier) {
        String normalized = identifier.trim();
        if ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("`") && normalized.endsWith("`"))
                || (normalized.startsWith("[") && normalized.endsWith("]"))) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}

final class LegacyWhereAnnotationRule extends AbstractHibernateRule {

    private static final String WHERE = "org.hibernate.annotations.Where";
    private static final String WHERE_JOIN_TABLE = "org.hibernate.annotations.WhereJoinTable";

    LegacyWhereAnnotationRule() {
        super(new HibernateRuleDefinition(
                "HIB-MAP-021",
                "Legacy @Where restrictions should be migrated",
                HibernateCategory.MAPPING,
                "MEDIUM",
                "Detects Hibernate @Where and @WhereJoinTable mappings, deprecated in ORM 6.3 and removed in ORM 7.",
                "Replace static restrictions with @SQLRestriction/@SQLJoinTableRestriction, or use @SoftDelete for"
                        + " supported soft-delete mappings.",
                "https://docs.jboss.org/hibernate/orm/7.0/migration-guide/migration-guide.html"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.targets(context.entities())) {
            if (entity.annotationInHierarchy(WHERE) != null || entity.annotationInHierarchy(WHERE_JOIN_TABLE) != null) {
                details.add(entity.name() + " uses a legacy @Where restriction.");
            }
            for (HibernateAttributeModel attribute : entity.attributes()) {
                if (attribute.annotation(WHERE) != null || attribute.annotation(WHERE_JOIN_TABLE) != null) {
                    details.add(attribute.description() + " uses a legacy @Where restriction.");
                }
            }
        }
        return violation(details);
    }
}

final class PrimitiveIdentifierOrVersionRule extends AbstractHibernateRule {

    PrimitiveIdentifierOrVersionRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-ENTITY-006",
                        "Review primitive-version newness semantics",
                        HibernateCategory.ENTITY_DESIGN,
                        "INFO",
                        "Primitive identifiers and versions are legal. For verified standard Spring Data JPA"
                                + " repositories, a primitive version cannot serve as the nullable-version newness"
                                + " signal.",
                        "Review the repository's actual save/persist/merge policy when nullable version detection is"
                                + " needed. Do not replace valid primitive IDs merely to satisfy a generic Hibernate"
                                + " warning.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#entity-pojo-identifier"));
    }

    // Optional Jakarta Persistence type: compare by class name instead of hard-referencing a class that may be
    // absent at runtime.
    @SuppressWarnings("java:S1872")
    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            if (HibernateRuleModelSupport.implementsPersistable(entity.javaType())) continue;
            boolean applicable = context.repositories().stream()
                    .anyMatch(repository -> entity.javaType().equals(repository.domainType())
                            && (!context.observed() || repository.standardJpaNewness()));
            if (context.observed()
                    && !applicable
                    && context.repositories().stream()
                            .anyMatch(repository -> entity.javaType().equals(repository.domainType())))
                context.missingEvidence();
            if (!applicable) continue;
            for (HibernateAttributeModel attribute :
                    context.targets(entity.attributes(), HibernateAttributeModel::hasVersion)) {
                if (attribute.hasVersion() && attribute.rawType().isPrimitive()) {
                    details.add(attribute.description() + " uses primitive "
                            + attribute.rawType().getName()
                            + "; standard Spring Data JPA newness falls back to identifier inspection.");
                }
            }
        }
        return violation(details);
    }
}

final class AssignedIdPersistableRule extends AbstractHibernateRule {

    AssignedIdPersistableRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-ENTITY-007",
                        "Review assigned-ID Spring Data newness",
                        HibernateCategory.ENTITY_DESIGN,
                        "MEDIUM",
                        "Reviews assigned identifiers without a nullable-version or Persistable newness signal only"
                                + " for verified standard Spring Data JPA save behavior; custom save and generator"
                                + " behavior is not inferred.",
                        "Review standard Spring Data newness handling for assigned IDs. Use a nullable version or"
                                + " intentional Persistable state when appropriate; custom save/newness logic is not"
                                + " inferred and SQL counts are not predicted.",
                        "https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html#jpa.entity-persistence.saving-entities.strategies"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        // The concern is specific to Spring Data JPA's generic save(): it inspects the id/version to guess whether
        // an entity is new. Quarkus/Panache calls entityManager.persist() directly and has no such ambiguity, so
        // without Spring Data Commons on the classpath there is nothing to recommend implementing Persistable for.
        if (!HibernateRuleModelSupport.isSpringDataPersistableAvailable()
                || context.repositories().isEmpty()) {
            return skipped("No Spring Data repository metadata was detected; this check only applies to Spring Data JPA"
                    + " repository domain types.");
        }
        Set<Class<?>> repositoryDomainTypes = context.repositories().stream()
                .filter(repository -> !context.observed() || repository.standardJpaNewness())
                .map(HibernateRepositoryModel::domainType)
                .collect(java.util.stream.Collectors.toSet());
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            if (context.observed()
                    && context.repositories().stream()
                            .anyMatch(repository -> entity.javaType() != null
                                    && entity.javaType().equals(repository.domainType())
                                    && !repository.standardJpaNewness())) {
                context.missingEvidence();
                continue;
            }
            if (entity.javaType() == null || !repositoryDomainTypes.contains(entity.javaType())) {
                continue;
            }
            context.evidence().markApplicable(true);
            boolean hasGeneratedId = entity.attributes().stream().anyMatch(a -> a.generatedValueAnnotation() != null);
            boolean hasVersion = entity.attributes().stream()
                    .anyMatch(attribute ->
                            attribute.hasVersion() && !attribute.rawType().isPrimitive());
            if (hasGeneratedId || hasVersion) {
                continue;
            }
            if (entity.attributes().stream().anyMatch(HibernateRuleModelSupport::hasCustomIdentifierGenerator)) {
                context.missingEvidence();
                continue;
            }

            boolean assigned = entity.attributes().stream()
                    .anyMatch(attribute ->
                            attribute.hasId() || attribute.annotation("jakarta.persistence.EmbeddedId") != null);
            if (!assigned) {
                context.missingEvidence();
                continue;
            }
            if (!HibernateRuleModelSupport.implementsPersistable(entity.javaType())) {
                details.add(entity.name()
                        + " has an assigned identifier and no nullable @Version; standard Spring Data save"
                        + " uses identifier newness unless Persistable is implemented.");
            }
        }
        return violation(details);
    }
}

final class EagerToOneFetchJoinRule extends AbstractHibernateRule {

    EagerToOneFetchJoinRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-QUERY-005",
                        "Eager to-one associations should be JOIN FETCHed in entity-returning queries",
                        HibernateCategory.QUERY,
                        "INFO",
                        "Reviews eligible entity-returning JPQL queries that omit declared eager to-one fetches."
                                + " Mapping @Fetch(JOIN) is not a JPQL exemption; secondary loads are possible but query"
                                + " counts are not measured.",
                        "JOIN FETCH the eager to-one association in the query, or map it FetchType.LAZY (see"
                                + " HIB-FETCH-001) and fetch it explicitly only where needed.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching-strategies"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.repositories().isEmpty()) {
            return skipped("No repository metadata was detected.");
        }
        List<String> details = new ArrayList<>();
        for (HibernateRepositoryModel repository : context.repositories()) {
            for (HibernateRepositoryMethodModel method : repository.methods()) {
                if (method.nativeQuery() || !method.hasQuery() || !method.returnsMultiple()) {
                    continue;
                }
                if (!HibernateRuleModelSupport.selectsWholeRootEntity(method.query())) {
                    continue;
                }
                HibernateEntityModel domainEntity = HibernateQueryShape.entityRoot(context, method);
                if (domainEntity == null) continue;
                if (method.evidence().entityGraph()) {
                    context.missingEvidence();
                    continue;
                }
                List<HibernateAttributeModel> eagerToOne = eagerToOneAssociations(domainEntity);
                if (!eagerToOne.isEmpty()) context.evidence().markApplicable(true);
                Set<String> fetched = fetchedAttributes(method.query());
                List<String> uncovered = new ArrayList<>();
                for (HibernateAttributeModel association : eagerToOne) {
                    if (!fetched.contains(association.name())) {
                        uncovered.add(association.name());
                    }
                }
                if (!uncovered.isEmpty()) {
                    details.add(method.description() + " selects whole entities but does not JOIN FETCH eager to-one "
                            + String.join(", ", uncovered) + "; secondary loads are possible, not measured.");
                }
            }
        }
        return violation(details);
    }

    private List<HibernateAttributeModel> eagerToOneAssociations(HibernateEntityModel entity) {
        List<HibernateAttributeModel> eager = new ArrayList<>();
        for (HibernateAttributeModel attribute : entity.attributes()) {
            if (!attribute.isToOneAssociation()) {
                continue;
            }
            Annotation association = attribute.associationAnnotation();
            if (!"EAGER".equals(attribute.annotationValueName(association, "fetch"))) {
                continue;
            }
            eager.add(attribute);
        }
        return eager;
    }

    private Set<String> fetchedAttributes(String query) {
        Set<String> fetched = new HashSet<>();
        String rootAlias = HibernateRuleModelSupport.rootAlias(query);
        for (String path : HibernateRuleModelSupport.joinFetchPaths(query)) {
            String attribute = HibernateRuleModelSupport.directAttribute(rootAlias, path);
            if (attribute != null) {
                fetched.add(attribute);
            }
        }
        return fetched;
    }
}

final class EntityProjectionQueryRule extends AbstractHibernateRule {

    EntityProjectionQueryRule() {
        super(new HibernateRuleDefinition(
                "HIB-QUERY-006",
                "Paged or streamed reads should prefer DTO projections over whole entities",
                HibernateCategory.QUERY,
                "INFO",
                "Detects paged or streamed Spring Data JPQL @Query methods (Pageable parameter, or Page/Slice/Stream"
                        + " return) that select whole entities instead of a constructor expression or interface/DTO"
                        + " projection.",
                "Consider an explicit DTO or closed projection when only part of an entity is needed, and verify the"
                        + " selected columns. Whole-entity reads remain valid when the use case needs managed state; not"
                        + " every projection avoids entity loading.",
                "https://docs.spring.io/spring-data/jpa/reference/repositories/projections.html"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.repositories().isEmpty()) {
            return skipped("No repository metadata was detected.");
        }
        List<String> details = new ArrayList<>();
        for (HibernateRepositoryModel repository : context.repositories()) {
            for (HibernateRepositoryMethodModel method : repository.methods()) {
                if (method.nativeQuery() || !method.hasQuery()) {
                    continue;
                }
                boolean pagedOrStreamed = method.hasPageableParameter()
                        || method.returnsPage()
                        || method.returnsSlice()
                        || method.returnsStream();
                if (!pagedOrStreamed) {
                    continue;
                }
                context.evidence().markApplicable(true);
                if (HibernateRuleModelSupport.selectsWholeRootEntity(method.query())
                        && HibernateQueryShape.entityRoot(context, method) != null) {
                    details.add(method.description()
                            + " returns whole entities from a paged/streamed @Query; consider a DTO/interface"
                            + " projection.");
                }
            }
        }
        return violation(details);
    }
}

final class MissingVersionRule extends AbstractHibernateRule {

    MissingVersionRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-ENTITY-008",
                        "Mutable entities should declare @Version for optimistic locking",
                        HibernateCategory.ENTITY_DESIGN,
                        "INFO",
                        "Detects mutable mapped entities (entities with non-identifier persistent state) that do not"
                                + " declare a @Version attribute and do not opt into Hibernate versionless optimistic"
                                + " locking or @Immutable.",
                        "Add a @Version attribute (for example a Long or Instant) so concurrent updates fail fast"
                                + " instead of silently overwriting each other; skip this only for append-only, read-only,"
                                + " or reference data.",
                        "https://jakarta.ee/specifications/persistence/3.1/apidocs/jakarta.persistence/jakarta/persistence/version"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.targets(context.entities())) {
            if (entity.hasVersionAttribute()
                    || entity.annotationInHierarchy("org.hibernate.annotations.Immutable") != null) {
                continue;
            }
            String optimisticLockingType = entity.annotationValueName(
                    entity.annotationInHierarchy("org.hibernate.annotations.OptimisticLocking"), "type");
            if ("DIRTY".equals(optimisticLockingType) || "ALL".equals(optimisticLockingType)) {
                continue;
            }
            if (hasMutableState(entity)) {
                details.add(entity.name()
                        + " has mutable persistent state but no @Version field, so concurrent updates can"
                        + " silently overwrite one another.");
            }
        }
        return violation(details);
    }

    private boolean hasMutableState(HibernateEntityModel entity) {
        for (HibernateAttributeModel attribute : entity.attributes()) {
            if (attribute.hasId()
                    || attribute.hasVersion()
                    || attribute.isAssociation()
                    || attribute.annotation("jakarta.persistence.EmbeddedId") != null
                    || attribute.annotation("jakarta.persistence.Transient") != null) {
                continue;
            }
            return true;
        }
        return false;
    }
}

final class NaturalIdCandidateRule extends AbstractHibernateRule {

    private static final String NATURAL_ID = "org.hibernate.annotations.NaturalId";

    NaturalIdCandidateRule() {
        super(new HibernateRuleDefinition(
                "HIB-ENTITY-009",
                "Unique business-key columns should consider @NaturalId",
                HibernateCategory.ENTITY_DESIGN,
                "INFO",
                "Detects entities with a @Column(unique = true) attribute, or a @Table(uniqueConstraints = ...)"
                        + " constraint, that have no attribute annotated org.hibernate.annotations.NaturalId.",
                "Consider @NaturalId for a genuine business lookup key. The annotation enables natural-id resolution"
                        + " but does not automatically reroute existing repository queries through natural-id lookup or"
                        + " cache APIs.",
                "https://docs.hibernate.org/orm/current/userguide/html_single/Hibernate_User_Guide.html#naturalid"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.targets(context.entities())) {
            boolean hasNaturalId =
                    entity.attributes().stream().anyMatch(attribute -> attribute.annotation(NATURAL_ID) != null);
            if (hasNaturalId) {
                continue;
            }
            for (HibernateAttributeModel attribute : entity.attributes()) {
                Annotation column = attribute.columnAnnotation();
                if (column != null && Boolean.TRUE.equals(attribute.annotationBooleanValue(column, "unique"))) {
                    details.add(attribute.description()
                            + " is a unique column with no @NaturalId attribute on this entity; if it is a"
                            + " business key (email, ISBN, order number, ...), consider"
                            + " org.hibernate.annotations.NaturalId.");
                }
            }
            Set<String> tableUniqueColumns = tableUniqueConstraintColumns(entity.javaType());
            if (!tableUniqueColumns.isEmpty()) {
                details.add(entity.name() + " declares a @Table unique constraint on column(s) "
                        + String.join(", ", tableUniqueColumns)
                        + " with no @NaturalId attribute; if this is a business key, consider"
                        + " org.hibernate.annotations.NaturalId.");
            }
        }
        return violation(details);
    }

    // Optional JPA type: compare by class name instead of hard-referencing a class that may be absent at runtime.
    @SuppressWarnings("java:S1872")
    private Set<String> tableUniqueConstraintColumns(Class<?> javaType) {
        Set<String> columns = new LinkedHashSet<>();
        Class<?> current = javaType;
        while (current != null && current != Object.class) {
            for (Annotation ann : current.getDeclaredAnnotations()) {
                if (!"jakarta.persistence.Table".equals(ann.annotationType().getName())) {
                    continue;
                }
                try {
                    Annotation[] constraints = (Annotation[])
                            ann.annotationType().getMethod("uniqueConstraints").invoke(ann);
                    for (Annotation constraint : constraints) {
                        Object names = constraint
                                .annotationType()
                                .getMethod("columnNames")
                                .invoke(constraint);
                        if (names instanceof String[] columnNames) {
                            for (String name : columnNames) {
                                if (name != null && !name.isBlank()) {
                                    columns.add(name.trim().toLowerCase(Locale.ROOT));
                                }
                            }
                        }
                    }
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }
            current = current.getSuperclass();
        }
        return columns;
    }
}

final class IdentityDisablesBatchingRule extends AbstractHibernateRule {

    IdentityDisablesBatchingRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-ID-006",
                        "GenerationType.IDENTITY disables JDBC batch inserts",
                        HibernateCategory.IDENTIFIERS,
                        "HIGH",
                        "Detects entities using @GeneratedValue(strategy=IDENTITY) while hibernate.jdbc.batch_size is"
                                + " configured; Hibernate cannot batch inserts for IDENTITY-generated keys because it must"
                                + " read each generated key back immediately.",
                        "Switch IDENTITY identifiers to SEQUENCE with a pooled allocationSize so Hibernate can batch"
                                + " inserts, or drop the JDBC batch size expectation for these entities.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#batch-session-batch"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        Integer batchSize = context.firstIntegerProperty(
                "spring.jpa.properties.hibernate.jdbc.batch_size", "hibernate.jdbc.batch_size");
        if (batchSize == null || batchSize <= 1) {
            return skipped(
                    "hibernate.jdbc.batch_size is not configured with a positive value, so there is no insert batching"
                            + " for IDENTITY generation to disable.");
        }
        List<String> details = new ArrayList<>();
        context.evidence().markApplicable(false);
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute :
                    context.targets(entity.attributes(), HibernateAttributeModel::hasGeneratedValue)) {
                Annotation generatedValue = attribute.generatedValueAnnotation();
                if (generatedValue == null) {
                    continue;
                }
                if ("IDENTITY".equals(attribute.annotationValueName(generatedValue, "strategy"))) {
                    details.add(attribute.description()
                            + " uses GenerationType.IDENTITY, so Hibernate cannot batch its inserts despite"
                            + " hibernate.jdbc.batch_size="
                            + batchSize + ".");
                }
            }
        }
        return violation(details);
    }
}

final class CompositeIdentifierContractRule extends AbstractHibernateRule {

    private static final String EMBEDDED_ID = "jakarta.persistence.EmbeddedId";
    private static final String ID_CLASS = "jakarta.persistence.IdClass";

    CompositeIdentifierContractRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-ID-007",
                        "Composite identifier classes must satisfy the JPA contract",
                        HibernateCategory.IDENTIFIERS,
                        "HIGH",
                        "Checks Persistence 3.2 composite-key constructor and equality-method structure. Records are"
                                + " supported and Serializable is not a blanket requirement. Equality contents are not"
                                + " inspected.",
                        "For non-record composite keys, provide the required public/protected no-arg constructor and"
                                + " paired equality methods. Review equality contents separately.",
                        "https://docs.hibernate.org/orm/current/userguide/html_single/Hibernate_User_Guide.html#identifiers-composite"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        Set<Class<?>> checked = new HashSet<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
                if (attribute.annotation(EMBEDDED_ID) != null) {
                    context.evidence().markApplicable(true);
                    checkCompositeIdClass(
                            attribute.rawType(), attribute.description() + " (@EmbeddedId)", checked, details);
                }
            }
            Class<?> idClass = idClassValue(entity.annotationInHierarchy(ID_CLASS));
            if (idClass != null) {
                context.evidence().markApplicable(true);
                checkCompositeIdClass(idClass, entity.name() + " (@IdClass)", checked, details);
            }
        }
        return violation(details);
    }

    private static void checkCompositeIdClass(
            Class<?> idClass, String description, Set<Class<?>> checked, List<String> details) {
        if (idClass == null || !checked.add(idClass)) {
            return;
        }
        List<String> problems = new ArrayList<>();
        if (!idClass.isRecord() && !hasPublicOrProtectedNoArgConstructor(idClass)) {
            problems.add("no public/protected no-arg ctor");
        }
        if (!overrides(idClass, "equals", Object.class)) {
            problems.add("no equals() override");
        }
        if (!overrides(idClass, "hashCode")) {
            problems.add("no hashCode() override");
        }
        if (!problems.isEmpty()) {
            // Simple name only: the entity description already gives full package context, and this keeps
            // the (truncation-bounded) detail message well within HibernateRuleSupport.detail()'s budget.
            details.add(
                    description + ": id class " + idClass.getSimpleName() + " is " + String.join(", ", problems) + ".");
        }
    }

    private static boolean hasPublicOrProtectedNoArgConstructor(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            int modifiers = constructor.getModifiers();
            return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }

    private static boolean overrides(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getMethod(name, parameterTypes).getDeclaringClass() != Object.class;
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }

    private static Class<?> idClassValue(Annotation annotation) {
        if (annotation == null) {
            return null;
        }
        try {
            Object value = annotation.annotationType().getMethod("value").invoke(annotation);
            return value instanceof Class<?> classValue ? classValue : null;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Composite identifier metadata is unavailable.", ex);
        }
    }
}

final class UnidirectionalOneToManyJoinColumnRule extends AbstractHibernateRule {

    UnidirectionalOneToManyJoinColumnRule() {
        super(new HibernateRuleDefinition(
                "HIB-MAP-020",
                "Review writable unidirectional one-to-many join-column DML",
                HibernateCategory.MAPPING,
                "MEDIUM",
                "Reviews writable unidirectional one-to-many join columns for mapping-dependent extra DML. Composite"
                        + " join columns are exempt only when every column is read-only.",
                "Inspect actual DML before changing relationship ownership. Consider a bidirectional association when"
                        + " it fits the domain; composite join columns are exempt only when every column is"
                        + " insertable=false and updatable=false.",
                "https://vladmihalcea.com/the-best-way-to-map-a-onetomany-relationship-with-jpa-and-hibernate/"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute :
                    context.targets(entity.attributes(), HibernateAttributeModel::isOneToMany)) {
                Annotation oneToMany = attribute.oneToManyAnnotation();
                if (!attribute.isOneToMany() || oneToMany == null) {
                    continue;
                }
                String mappedBy = attribute.annotationStringValue(oneToMany, "mappedBy");
                if (mappedBy != null && !mappedBy.isBlank()) {
                    continue;
                }
                if (!attribute.hasJoinColumn() || isReadOnlyJoinColumn(attribute)) {
                    continue;
                }
                details.add(attribute.description()
                        + " is a writable unidirectional @OneToMany with join columns; inspect possible extra"
                        + " UPDATE statements for the effective mapping.");
            }
        }
        return violation(details);
    }

    private boolean isReadOnlyJoinColumn(HibernateAttributeModel attribute) {
        Annotation joinColumn = attribute.joinColumnAnnotation();
        if (joinColumn == null) {
            Annotation columns = attribute.annotation("jakarta.persistence.JoinColumns");
            if (columns == null) return false;
            try {
                Annotation[] members = (Annotation[])
                        columns.annotationType().getMethod("value").invoke(columns);
                if (members.length == 0) return false;
                for (Annotation member : members) {
                    if (!Boolean.FALSE.equals(attribute.annotationBooleanValue(member, "insertable"))
                            || !Boolean.FALSE.equals(attribute.annotationBooleanValue(member, "updatable")))
                        return false;
                }
                return true;
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Composite join-column metadata is unavailable.", ex);
            }
        }
        Boolean insertable = attribute.annotationBooleanValue(joinColumn, "insertable");
        Boolean updatable = attribute.annotationBooleanValue(joinColumn, "updatable");
        return Boolean.FALSE.equals(insertable) && Boolean.FALSE.equals(updatable);
    }
}

final class MultipleCollectionJoinFetchRule extends AbstractHibernateRule {

    MultipleCollectionJoinFetchRule() {
        super(new HibernateRuleDefinition(
                "HIB-QUERY-007",
                "Queries should not JOIN FETCH more than one collection",
                HibernateCategory.QUERY,
                "HIGH",
                "Reviews eligible JPQL queries fetching multiple direct-root collections. Parallel fetching can"
                        + " multiply rows; Java List declarations do not prove effective bag classification or a"
                        + " guaranteed exception.",
                "Consider fetching one collection at a time or bounded secondary loading. Entity graphs are not a"
                        + " universal remedy and changing collection semantics solely to avoid a warning is"
                        + " inappropriate.",
                "https://vladmihalcea.com/hibernate-multiplebagfetchexception/"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.repositories().isEmpty()) {
            return skipped("No repository metadata was detected.");
        }
        List<String> bagDetails = new ArrayList<>();
        List<String> collectionDetails = new ArrayList<>();
        for (HibernateRepositoryModel repository : context.repositories()) {
            for (HibernateRepositoryMethodModel method : repository.methods()) {
                if (method.nativeQuery() || method.query() == null) {
                    continue;
                }
                HibernateEntityModel domainEntity = HibernateQueryShape.entityRoot(context, method);
                if (domainEntity == null) continue;
                Map<String, Boolean> collectionIsBag = new LinkedHashMap<>();
                for (HibernateAttributeModel attribute : domainEntity.collectionAttributes()) {
                    collectionIsBag.put(attribute.name(), attribute.isBagAttribute());
                }
                String rootAlias = HibernateRuleModelSupport.rootAlias(method.query());
                if (rootAlias == null) {
                    continue;
                }
                if (!collectionIsBag.isEmpty()) context.evidence().markApplicable(true);
                Set<String> fetchedCollections = new LinkedHashSet<>();
                int bagCount = 0;
                for (String path : HibernateRuleModelSupport.joinFetchPaths(method.query())) {
                    String attributeName = HibernateRuleModelSupport.directAttribute(rootAlias, path);
                    if (attributeName == null || !collectionIsBag.containsKey(attributeName)) {
                        continue;
                    }
                    if (fetchedCollections.add(attributeName)
                            && Boolean.TRUE.equals(collectionIsBag.get(attributeName))) {
                        bagCount++;
                    }
                }
                if (fetchedCollections.size() < 2) {
                    continue;
                }
                String joined = String.join(", ", fetchedCollections);
                if (bagCount >= 2) {
                    bagDetails.add(method.description() + " JOIN FETCHes multiple bag collections (" + joined
                            + "); effective bag classification is unobserved, and parallel fetching may"
                            + " multiply rows.");
                } else {
                    collectionDetails.add(method.description() + " JOIN FETCHes multiple collections (" + joined
                            + "), which may multiply result rows.");
                }
            }
        }
        if (!bagDetails.isEmpty()) {
            List<String> all = new ArrayList<>(bagDetails);
            all.addAll(collectionDetails);
            return violation(HibernateRuleSupport.MEDIUM, all);
        }
        return violation(HibernateRuleSupport.MEDIUM, collectionDetails);
    }
}
