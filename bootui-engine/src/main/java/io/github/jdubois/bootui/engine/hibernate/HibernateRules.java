package io.github.jdubois.bootui.engine.hibernate;

import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import java.lang.annotation.Annotation;
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
        try {
            return evaluateRule(context);
        } catch (RuntimeException | LinkageError ex) {
            return HibernateRuleSupport.error(definition, "Rule could not be evaluated: " + ex.getMessage());
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

    private HibernateRuleModelSupport() {}

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
        if (query == null) {
            return null;
        }
        Matcher matcher = FROM_ALIAS.matcher(query);
        return matcher.find() ? matcher.group(1) : null;
    }

    static List<String> joinFetchPaths(String query) {
        List<String> paths = new ArrayList<>();
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
        int dot = remainder.indexOf('.');
        return dot == -1 ? remainder : remainder.substring(0, dot);
    }

    /**
     * Returns one detail per Spring Data repository method that pages (Pageable parameter) a JPQL query whose
     * {@code JOIN FETCH} targets a collection association declared directly on the query root. Shared by HIB-FETCH-003
     * (which reports these as violations) and HIB-CONFIG-016 (which uses their presence to pick a dynamic severity).
     */
    static List<String> paginatedCollectionFetchFindings(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateRepositoryModel repository : context.repositories()) {
            HibernateEntityModel domainEntity = entityForDomainType(context, repository.domainType());
            if (domainEntity == null) {
                continue;
            }
            Set<String> collectionNames = collectionAttributeNames(domainEntity);
            for (HibernateRepositoryMethodModel method : repository.methods()) {
                if (!method.hasPageableParameter() || method.nativeQuery() || method.query() == null) {
                    continue;
                }
                String rootAlias = rootAlias(method.query());
                if (rootAlias == null) {
                    continue;
                }
                for (String path : joinFetchPaths(method.query())) {
                    String attributeName = directAttribute(rootAlias, path);
                    if (attributeName != null && collectionNames.contains(attributeName)) {
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
}

final class EagerFetchRule extends AbstractHibernateRule {

    EagerFetchRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-FETCH-001",
                        "Eager fetching should stay explicit and bounded",
                        HibernateCategory.FETCHING,
                        "HIGH",
                        "Detects JPA associations and @ElementCollection attributes mapped with FetchType.EAGER, including default-eager to-one associations.",
                        "Prefer LAZY mappings and fetch required graphs or collection values explicitly with joins, entity graphs, or DTO queries.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
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
                        "Generated identifiers should avoid GenerationType.IDENTITY",
                        HibernateCategory.IDENTIFIERS,
                        "MEDIUM",
                        "Detects identifiers using GenerationType.IDENTITY, which prevents JDBC batch inserts.",
                        "Prefer SEQUENCE with allocationSize and Hibernate's pooled optimizer when the database supports sequences.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#identifiers-generators"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
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
                        "Generated identifiers should avoid GenerationType.TABLE",
                        HibernateCategory.IDENTIFIERS,
                        "HIGH",
                        "Detects identifiers using GenerationType.TABLE, which serializes id allocation through a table row.",
                        "Prefer SEQUENCE with a pooled allocation size, or IDENTITY only when the database has no sequence support.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#identifiers-generators"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
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
                        "@SequenceGenerator should use pooled allocation",
                        HibernateCategory.IDENTIFIERS,
                        "MEDIUM",
                        "Detects @SequenceGenerator declarations with allocationSize=1.",
                        "Use an allocation size greater than 1 and keep it aligned with the database sequence increment.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#identifiers-generators-sequence"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            Annotation entitySequence = entity.annotation("jakarta.persistence.SequenceGenerator");
            if (allocationSizeIsOne(entitySequence, entity)) {
                details.add(entity.name() + " declares @SequenceGenerator(allocationSize=1).");
            }
            for (HibernateAttributeModel attribute : entity.attributes()) {
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
                        "Detects unidirectional @OneToMany mappings without mappedBy or @JoinColumn, which Hibernate maps through a join table and maintains with extra DELETE/INSERT statements.",
                        "Prefer a bidirectional association: put @ManyToOne on the child and @OneToMany(mappedBy=...) on the parent so the child's foreign key owns the relationship. If a unidirectional mapping is intentional, add @JoinColumn to drop the join table (note the extra UPDATE statements flagged by HIB-MAP-020).",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#associations-one-to-many"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
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
                        "Many-to-many associations should use Set semantics",
                        HibernateCategory.MAPPING,
                        "MEDIUM",
                        "Detects @ManyToMany associations declared as List, which can trigger delete-and-reinsert DML.",
                        "Use Set for many-to-many associations, or model the join table as an entity when it has business meaning.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#associations-many-to-many"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
                if (attribute.isManyToMany() && attribute.isListAttribute()) {
                    details.add(attribute.description() + " is @ManyToMany and declared as a List.");
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
                        "Remove REMOVE/ALL cascades from many-to-many associations; model the join table as an entity when lifecycle ownership is needed.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#pc-cascade"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
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
                        "Remove REMOVE/ALL cascades from many-to-one associations so deletes do not propagate from children to shared parents.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#pc-cascade"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
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
                        "Use @MapsId for dependent one-to-one entities when the child row has the same lifecycle and identifier as the parent.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#identifiers-derived"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> dependentDetails = new ArrayList<>();
        List<String> plainDetails = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
                Annotation oneToOne = attribute.oneToOneAnnotation();
                if (oneToOne == null || attribute.hasMapsId() || attribute.hasId()) {
                    continue;
                }
                String mappedBy = attribute.annotationStringValue(oneToOne, "mappedBy");
                if (mappedBy != null && !mappedBy.isBlank()) {
                    continue;
                }
                if (hasDependentSignal(attribute, oneToOne)) {
                    dependentDetails.add(
                            attribute.description()
                                    + " is an owning @OneToOne that looks lifecycle-dependent (optional=false or cascade REMOVE/ALL) but does not use @MapsId.");
                } else {
                    plainDetails.add(
                            attribute.description()
                                    + " is an owning @OneToOne without @MapsId; consider a shared primary key when the child shares the parent's lifecycle and identifier.");
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
                        "Entity inheritance should avoid TABLE_PER_CLASS",
                        HibernateCategory.MAPPING,
                        "MEDIUM",
                        "Detects @Inheritance(strategy = TABLE_PER_CLASS), which requires UNION queries for polymorphic loads.",
                        "Prefer SINGLE_TABLE or JOINED inheritance unless every subtype is queried independently.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#entity-inheritance"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
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
                        "Detects Hibernate @NotFound(action = IGNORE), which hides missing references and forces eager resolution.",
                        "Fix referential integrity or model optional data explicitly instead of suppressing missing target rows.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#associations-not-found"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
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
            for (HibernateAttributeModel attribute : entity.attributes()) {
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
                        "Entities should avoid multiple bag collections",
                        HibernateCategory.FETCHING,
                        "MEDIUM",
                        "Detects entities with two or more unordered List/Collection associations.",
                        "Fetch at most one bag collection per query, add @OrderColumn when list order is persistent, or split loading into targeted queries.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching-strategies"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            List<String> bagNames = entity.collectionAttributes().stream()
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
                "Detects enum attributes that omit @Enumerated and therefore rely on JPA's ORDINAL default.",
                "Declare the enum mapping explicitly: use STRING, a database-native enum type, a stable converter/code, or an intentional ORDINAL mapping backed by append-only enum ordering and a lookup table or database constraint.",
                "https://vladmihalcea.com/the-best-way-to-map-an-enum-type-with-jpa-and-hibernate/"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
                if (!attribute.isEnumAttribute()) {
                    continue;
                }
                Annotation enumerated = attribute.enumeratedAnnotation();
                if (enumerated == null && !attribute.hasConvertAnnotation()) {
                    details.add(attribute.description() + " relies on JPA's default ORDINAL enum storage.");
                }
            }
        }
        return violation(details);
    }
}

final class OpenInViewRule extends AbstractHibernateRule {

    OpenInViewRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CONFIG-001",
                        "Open Session in View should be disabled",
                        HibernateCategory.CONFIGURATION,
                        "MEDIUM",
                        "Detects spring.jpa.open-in-view=true, including Spring Boot's default when the property is not set.",
                        "Set spring.jpa.open-in-view=false and fetch data inside transactional service boundaries.",
                        "https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.jpa-and-spring-data.open-in-view"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        Boolean value = context.booleanProperty("spring.jpa.open-in-view");
        if (Boolean.FALSE.equals(value)) {
            return pass();
        }
        String detail = value == null
                ? "spring.jpa.open-in-view is not set, so Spring Boot's web default enables it."
                : "spring.jpa.open-in-view=true is enabled.";
        return violation(List.of(detail));
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
                        "Detects lazy to-one and collection associations that can initialize through secondary selects without hibernate.default_batch_fetch_size or an applicable @BatchSize.",
                        "Set a bounded hibernate.default_batch_fetch_size or targeted @BatchSize for associations traversed across multiple owner rows; use explicit fetch plans or paged queries for a single oversized collection.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching-batch"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (!context.hasAssociations()) {
            return skipped("No mapped associations were detected.");
        }
        if (context.defaultBatchFetchSize() != null) {
            return pass();
        }
        Map<String, HibernateEntityModel> entitiesByJavaType =
                HibernateRuleModelSupport.entitiesByJavaType(context.entities());
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
                if (!isBatchFetchCandidate(attribute) || isCoveredByBatchSize(attribute, entitiesByJavaType)) {
                    continue;
                }
                details.add(
                        attribute.description()
                                + " can initialize through secondary selects without a global batch-fetch size or applicable @BatchSize.");
            }
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
                        "Page root ids first, then fetch the required collection graph in a second query inside the same transaction.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#hql-fetching"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.hasHibernateCollectionFetchPaginationFix()) {
            return skipped(
                    "Hibernate "
                            + context.hibernateVersionDisplay()
                            + " is 7.4 or newer, where pagination over a collection JOIN FETCH is handled in memory with a warning instead of the legacy silent full-table fetch.");
        }
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
                        "Remove this setting and fetch required data inside transaction boundaries with explicit fetch plans or DTO queries.",
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
                        "Detects missing or non-positive hibernate.jdbc.batch_size.",
                        "Set a bounded JDBC batch size such as 25 for write-capable applications, then tune it with representative workloads.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#batch-session-batch"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        Integer batchSize = context.firstIntegerProperty(
                "spring.jpa.properties.hibernate.jdbc.batch_size", "hibernate.jdbc.batch_size");
        if (batchSize != null && batchSize > 0) {
            return pass();
        }
        return violation(List.of("hibernate.jdbc.batch_size is not configured with a positive value."));
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
                        "Enable order_inserts and order_updates so same-table statements are grouped into larger JDBC batches.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#batch-session-batch"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        Integer batchSize = context.firstIntegerProperty(
                "spring.jpa.properties.hibernate.jdbc.batch_size", "hibernate.jdbc.batch_size");
        if (batchSize == null || batchSize <= 0) {
            return skipped("JDBC batching is not configured.");
        }
        List<String> details = new ArrayList<>();
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
                        "Configure a bounded slow-query threshold in development and staging profiles to surface expensive SQL early.",
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
                        "Enable statistics in development or performance-test profiles when investigating query counts, cache efficiency, and fetch plans.",
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
                        "Detects resource-local configurations where hibernate.connection.provider_disables_autocommit is not enabled.",
                        "When the connection pool disables auto-commit, set hibernate.connection.provider_disables_autocommit=true so Hibernate can delay connection acquisition.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#database-connectionprovider"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
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
            return violation(
                    List.of(
                            "spring.datasource.hikari.auto-commit=false but hibernate.connection.provider_disables_autocommit is not enabled, so Hibernate acquires the JDBC connection eagerly on transaction start."));
        }
        return skipped(
                "Auto-commit handling could not be confirmed; set hibernate.connection.provider_disables_autocommit=true when the connection pool disables auto-commit.");
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
                        "Detects repository queries with collection parameters when hibernate.query.in_clause_parameter_padding is disabled.",
                        "Enable IN-clause parameter padding when variable-length IN predicates are common and the database benefits from plan reuse.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#configurations-query"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.isPropertyTrue(
                "spring.jpa.properties.hibernate.query.in_clause_parameter_padding",
                "hibernate.query.in_clause_parameter_padding")) {
            return pass();
        }
        List<String> details = new ArrayList<>();
        for (HibernateRepositoryModel repository : context.repositories()) {
            for (HibernateRepositoryMethodModel method : repository.methods()) {
                if (method.nativeQuery() || method.query() == null || !method.hasCollectionParameter()) {
                    continue;
                }
                if (hasInPredicate(method.query())) {
                    details.add(method.description() + " has a collection parameter in an IN predicate.");
                }
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
                        "Query cache requires a second-level cache provider",
                        HibernateCategory.CONFIGURATION,
                        "HIGH",
                        "Detects hibernate.cache.use_query_cache=true without a second-level cache provider.",
                        "Disable query caching or configure a second-level cache region factory and cache the entities returned by cacheable entity queries.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#caching-query"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
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
            return violation(List.of("Query cache is enabled without a configured second-level cache region factory."));
        }
        return pass();
    }
}

final class CacheableWithoutCacheStrategyRule extends AbstractHibernateRule {

    CacheableWithoutCacheStrategyRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CONFIG-011",
                        "Cacheable entities should declare an explicit cache strategy",
                        HibernateCategory.CONFIGURATION,
                        "MEDIUM",
                        "Detects JPA @Cacheable entities without Hibernate @Cache when second-level caching appears configured.",
                        "Add an explicit Hibernate cache concurrency strategy or remove @Cacheable if the entity should not use the second-level cache.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#caching"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        String regionFactory = context.firstProperty(
                "spring.jpa.properties.hibernate.cache.region.factory_class", "hibernate.cache.region.factory_class");
        if (regionFactory == null
                && !context.isPropertyTrue(
                        "spring.jpa.properties.hibernate.cache.use_second_level_cache",
                        "hibernate.cache.use_second_level_cache")) {
            return skipped("Second-level caching is not configured.");
        }
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            if (entity.isJpaCacheable() && !entity.hasHibernateCacheAnnotation()) {
                details.add(entity.name() + " uses @Cacheable without an explicit Hibernate @Cache strategy.");
            }
        }
        return violation(details);
    }
}

final class RiskyDdlAutoRule extends AbstractHibernateRule {

    private static final List<String> RISKY_VALUES = List.of("update", "create", "create-drop");

    RiskyDdlAutoRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CONFIG-002",
                        "Schema generation should not mutate non-test databases",
                        HibernateCategory.CONFIGURATION,
                        "INFO",
                        "Detects ddl-auto values that update, create, or drop schemas outside test profiles.",
                        "Use versioned migrations for shared databases and reserve ddl-auto=create/create-drop/update for disposable test environments.",
                        "https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.jpa-and-spring-data.creating-and-dropping"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        String ddlAuto = context.firstProperty(
                "spring.jpa.hibernate.ddl-auto",
                "spring.jpa.properties.hibernate.hbm2ddl.auto",
                "hibernate.hbm2ddl.auto");
        if (ddlAuto == null) {
            return pass();
        }
        String normalized = ddlAuto.toLowerCase(Locale.ROOT);
        if (!RISKY_VALUES.contains(normalized)) {
            return pass();
        }
        String[] profiles = context.activeProfiles().toArray(new String[0]);
        boolean creates = normalized.equals("create") || normalized.equals("create-drop");
        // Highest-risk profile wins: a production-like profile is dangerous even if a test/dev profile is also active.
        if (context.isProductionProfileActive()) {
            String severity = creates ? HibernateRuleSupport.CRITICAL : HibernateRuleSupport.HIGH;
            return violation(
                    severity,
                    "ddl-auto is set to " + ddlAuto
                            + " while a production-like profile is active, so application startup can "
                            + (creates ? "drop and recreate" : "mutate") + " the live schema.");
        }
        if (hasTestProfile(profiles)) {
            return pass();
        }
        if (isDisposableProfile(profiles)) {
            return violation(
                    HibernateRuleSupport.INFO,
                    "ddl-auto is set to " + ddlAuto
                            + " under a dev/local profile; this is fine for a disposable database but must not reach shared or production environments.");
        }
        return violation(
                HibernateRuleSupport.MEDIUM,
                "ddl-auto is set to " + ddlAuto
                        + " with no profile pinning it to a disposable database; use versioned migrations for any shared database.");
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
                        "Implement equals and hashCode as a pair, and review generated identifier semantics before using entities in sets or maps.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#mapping-model-pojo-equalshashcode"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
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
                        "Add @DynamicUpdate when using versionless optimistic locking so UPDATE statements include the intended changed columns.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#locking-optimistic-versionless"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
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
                        "@Lob attributes should be loaded lazily",
                        HibernateCategory.FETCHING,
                        "MEDIUM",
                        "Detects @Lob attributes that do not declare @Basic(fetch=LAZY), so they are loaded with every entity hydration.",
                        "Annotate @Lob fields with @Basic(fetch = FetchType.LAZY) so large CLOB/BLOB payloads load only when accessed; bytecode enhancement is required for non-association lazy loading to actually defer the SQL.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#basic-binary"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
                if (attribute.isLob() && !attribute.hasBasicLazy()) {
                    details.add(attribute.description()
                            + " is annotated with @Lob but does not declare @Basic(fetch = LAZY).");
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
                        "Detects collection-valued associations annotated with @Fetch(FetchMode.JOIN), which forces every fetch path through a SQL JOIN and undermines pagination.",
                        "Prefer @Fetch(FetchMode.SELECT) or SUBSELECT for collections and request JOIN FETCH only on the specific query that needs the graph.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching-strategies"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
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

final class GeneratedValueWithoutStrategyRule extends AbstractHibernateRule {

    GeneratedValueWithoutStrategyRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-ID-004",
                        "@GeneratedValue should declare an explicit strategy",
                        HibernateCategory.IDENTIFIERS,
                        "MEDIUM",
                        "Detects @GeneratedValue without an explicit strategy, which defaults to AUTO and typically resolves to IDENTITY on databases like MySQL and PostgreSQL.",
                        "Pick the strategy that fits the database (SEQUENCE with allocationSize on Postgres/Oracle, IDENTITY only when truly required) and set it explicitly so the choice is reviewable.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#identifiers-generators-generated-value"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
                Annotation generated = attribute.generatedValueAnnotation();
                if (generated == null) {
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
                        "UUID identifiers should use @UuidGenerator",
                        HibernateCategory.IDENTIFIERS,
                        "LOW",
                        "Detects UUID identifiers that rely on @GeneratedValue without the Hibernate @UuidGenerator strategy.",
                        "Annotate UUID identifiers with @UuidGenerator (TIME for index-friendly v6/v7-style values) instead of inheriting the JPA default, which yields random v4 UUIDs that fragment B-tree indexes.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#identifiers-generators-uuid"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
                if (!attribute.hasId() || !attribute.isUuidType()) {
                    continue;
                }
                if (attribute.hasGeneratedValue() && !attribute.hasUuidGenerator()) {
                    details.add(attribute.description() + " is a UUID identifier without @UuidGenerator.");
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
                        "@ElementCollection List should persist order",
                        HibernateCategory.MAPPING,
                        "MEDIUM",
                        "Detects @ElementCollection List attributes that do not declare @OrderColumn or @OrderBy, so Hibernate treats every change as a delete-and-reinsert.",
                        "Add @OrderColumn for index-tracked lists or @OrderBy for query-time ordering; otherwise prefer Set<> or be aware that mutations rewrite the entire collection table.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#collections-list"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
                if (attribute.isElementCollection()
                        && attribute.isListAttribute()
                        && !attribute.hasOrderColumn()
                        && !attribute.hasOrderBy()) {
                    details.add(attribute.description()
                            + " is an @ElementCollection List without @OrderColumn or @OrderBy.");
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
                "HIGH",
                "Detects @Entity classes declared as final, which prevents Hibernate from creating runtime proxies for lazy associations.",
                "Remove the final modifier from entities (and avoid Kotlin classes without `open`) so lazy to-one associations and bytecode enhancement work correctly.",
                "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#entity"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            if (entity.isFinalClass()) {
                details.add(entity.name() + " is declared final.");
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
                "Detects @Inheritance(SINGLE_TABLE) roots without an explicit @DiscriminatorColumn, leaving the default name and length implicit.",
                "Declare @DiscriminatorColumn (with name, type, and length) on the SINGLE_TABLE root so schema generation and reviews see the chosen contract instead of provider defaults.",
                "https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1.html#a3158"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
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
                "Detects persistent String attributes without @Column(length=...), which defaults to 255 in generated DDL.",
                "Set @Column(length=...) to a value that matches the domain so generated DDL, validation, and database constraints stay aligned; consider @Lob or columnDefinition for free-text payloads.",
                "https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1.html#a2128"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
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
                "Detects BigDecimal attributes without @Column(precision=..., scale=...), which falls back to provider defaults that vary by database.",
                "Always set precision and scale on monetary or numeric @Column mappings so DDL generation and Bean Validation agree on rounding behavior.",
                "https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1.html#a2128"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
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
                        "Detects persistent attributes typed as java.util.Date, java.util.Calendar, or java.sql temporal types instead of java.time.",
                        "Migrate temporal fields to java.time (Instant, LocalDate, LocalDateTime, OffsetDateTime, ZonedDateTime) so JDBC binding is immutable, time-zone aware, and free of @Temporal boilerplate.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#basic-mapping-temporal"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
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
                        "Detects @ManyToOne associations whose @JoinColumn is non-nullable but whose mapping still allows optional=true (the default).",
                        "Set @ManyToOne(optional=false) when the foreign key is mandatory so Hibernate can avoid the secondary SELECT used to discriminate between null and a real proxy.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#associations-many-to-one"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
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

final class LazyOneToOneEnhancementRule extends AbstractHibernateRule {

    LazyOneToOneEnhancementRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-MAP-017",
                        "Lazy owning @OneToOne requires bytecode enhancement",
                        HibernateCategory.MAPPING,
                        "INFO",
                        "Detects optional owning @OneToOne associations declared LAZY without Hibernate bytecode enhancement enabled, so Hibernate silently fetches them eagerly.",
                        "Enable hibernate.bytecode.enhancer.enableLazyInitialization (and configure the enhancement plugin), or switch the relation to @MapsId so the existing foreign key drives loading.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#BytecodeEnhancement-lazy-loading"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.isHibernateEnhancementEnabled()) {
            return pass();
        }
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
                Annotation oneToOne = attribute.oneToOneAnnotation();
                if (oneToOne == null) {
                    continue;
                }
                String mappedBy = attribute.annotationStringValue(oneToOne, "mappedBy");
                if (mappedBy != null && !mappedBy.isBlank()) {
                    continue;
                }
                if (attribute.hasMapsId()) {
                    continue;
                }
                String fetch = attribute.annotationValueName(oneToOne, "fetch");
                Boolean optional = attribute.annotationBooleanValue(oneToOne, "optional");
                if ("LAZY".equals(fetch) && !Boolean.FALSE.equals(optional)) {
                    details.add(attribute.description()
                            + " is a lazy owning @OneToOne but bytecode enhancement is disabled.");
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
                        "Detects entities that override equals and hashCode while exposing JPA associations. Generated implementations (Lombok @Data/@EqualsAndHashCode without exclusions, IDE templates) typically include those associations and trigger lazy loads when entities are stored in collections.",
                        "Base equals/hashCode on a stable business key or natural id only. If associations must participate, exclude lazy ones explicitly and use the entity class to avoid proxy mismatches.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#mapping-model-pojo-equalshashcode"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
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
                        "Detects entities that override toString while exposing JPA associations. Generated implementations (Lombok @Data/@ToString without exclusions, IDE templates) typically traverse associations and trigger N+1 lazy loads or LazyInitializationException outside an open session.",
                        "Base toString on the identifier and a few stable scalar fields. Exclude associations explicitly (for example with @ToString(exclude=...)) so logging or debugging does not pull the object graph.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#mapping-model-pojo-tostring"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
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
                        "Detects entity attributes that are reachable as public fields, which lets callers bypass Hibernate's instrumentation for lazy loading and dirty tracking.",
                        "Keep persistent fields private (or package-private) and expose mutators when needed; this preserves proxy substitution and bytecode-enhancer guarantees.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#entity-pojo-accessors"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
                if (attribute.publicMember() && !attribute.name().endsWith("()")) {
                    details.add(attribute.description() + " is exposed as a public field.");
                }
            }
        }
        return violation(details);
    }
}

final class ModifyingClearAutomaticallyRule extends AbstractHibernateRule {

    ModifyingClearAutomaticallyRule() {
        super(new HibernateRuleDefinition(
                "HIB-QUERY-001",
                "@Modifying queries should clear or flush the persistence context",
                HibernateCategory.QUERY,
                "HIGH",
                "Detects Spring Data @Modifying queries that do not set clearAutomatically or flushAutomatically, so the persistence context can hold stale entities after the bulk update or delete.",
                "Set @Modifying(clearAutomatically=true) (and flushAutomatically=true when pending changes must be applied first), or evict affected entities before issuing the bulk statement.",
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
                if (!method.modifying()) {
                    continue;
                }
                if (!method.modifyingClearsAutomatically() && !method.modifyingFlushesAutomatically()) {
                    details.add(method.description() + " is @Modifying without clearAutomatically/flushAutomatically.");
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
                        "Streaming repository methods need a transactional, read-only scope",
                        HibernateCategory.QUERY,
                        "MEDIUM",
                        "Detects Spring Data repository methods that return java.util.stream.Stream. They keep the underlying JDBC cursor open and must run inside an open transaction with the caller closing the stream.",
                        "Wrap the caller in @Transactional(readOnly=true), consume the stream inside a try-with-resources block, and close it before the transaction ends; otherwise prefer Page<> or a bounded List<>.",
                        "https://docs.spring.io/spring-data/jpa/reference/repositories/query-methods-details.html#repositories.query-streaming"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.repositories().isEmpty()) {
            return skipped("No repository metadata was detected.");
        }
        List<String> details = new ArrayList<>();
        for (HibernateRepositoryModel repository : context.repositories()) {
            for (HibernateRepositoryMethodModel method : repository.methods()) {
                if (method.returnsStream()) {
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
                "Native paged @Query must declare countQuery",
                HibernateCategory.QUERY,
                "HIGH",
                "Detects native @Query methods with a Pageable parameter or Page<> return type that do not declare countQuery, leaving Spring Data unable to derive a correct COUNT statement.",
                "Add countQuery=... to the @Query so paging can compute totals; without it Spring Data either fails to start or executes a wrong COUNT.",
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
                if (!method.nativeQuery() || !method.hasQuery()) {
                    continue;
                }
                if (!method.hasPageableParameter() && !method.returnsPage()) {
                    continue;
                }
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
                "Detects derived deleteBy.../removeBy... repository methods. Spring Data implements them by selecting matching entities first and then deleting them one by one, which is expensive on large result sets.",
                "For bulk removals prefer an explicit @Modifying @Query(\"delete from ... where ...\") with clearAutomatically=true; reserve derived deleteBy for small or cascading deletes.",
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
                    details.add(method.description()
                            + " is a derived delete query; consider an explicit @Modifying bulk delete.");
                }
            }
        }
        return violation(details);
    }
}

final class SqlLoggingInProductionRule extends AbstractHibernateRule {

    SqlLoggingInProductionRule() {
        super(new HibernateRuleDefinition(
                "HIB-CONFIG-012",
                "SQL logging should be off when a production profile is active",
                HibernateCategory.CONFIGURATION,
                "MEDIUM",
                "Detects show-sql or DEBUG/TRACE logging for Hibernate SQL/binder categories while a production-like profile (prod, production, staging) is active.",
                "Disable spring.jpa.show-sql and keep org.hibernate.SQL / org.hibernate.orm.jdbc.bind at INFO or WARN in production; logging every statement degrades throughput dramatically.",
                "https://docs.spring.io/spring-boot/reference/features/logging.html"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (!context.isProductionProfileActive()) {
            return pass();
        }
        if (context.isSqlLoggingEnabled()) {
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
                        "Configure hibernate.jdbc.time_zone",
                        HibernateCategory.CONFIGURATION,
                        "LOW",
                        "Detects deployments that do not pin hibernate.jdbc.time_zone, leaving java.time conversions to use the JVM default zone.",
                        "Set spring.jpa.properties.hibernate.jdbc.time_zone=UTC (or another fixed zone) so JDBC binds/reads of timestamps are deterministic across hosts and migrations.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#basic-datetime-timezone"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
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
                        "Detects hibernate.connection.pool_size, which only activates Hibernate's internal connection pool, intended for testing and not for production load.",
                        "Remove hibernate.connection.pool_size and rely on Spring Boot's DataSource (HikariCP by default) so connections come from a tuned, monitored pool.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#database-connectionprovider"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        String value = context.firstProperty(
                "spring.jpa.properties.hibernate.connection.pool_size", "hibernate.connection.pool_size");
        if (value != null && !value.isBlank()) {
            return violation(List.of("hibernate.connection.pool_size is set to " + value
                    + "; switch to a managed DataSource (for example HikariCP)."));
        }
        return pass();
    }
}

final class DeferDatasourceInitializationRule extends AbstractHibernateRule {

    DeferDatasourceInitializationRule() {
        super(new HibernateRuleDefinition(
                "HIB-CONFIG-015",
                "spring.jpa.defer-datasource-initialization is only safe with embedded DDL flows",
                HibernateCategory.CONFIGURATION,
                "MEDIUM",
                "Detects spring.jpa.defer-datasource-initialization=true while ddl-auto is none/validate; the setting is meaningful only when Hibernate generates the schema (create/create-drop/update).",
                "Combine defer-datasource-initialization=true with ddl-auto=create or create-drop, or remove it when relying on Flyway/Liquibase so data.sql is loaded by the migration tool instead.",
                "https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.datasource.initialization"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (!context.isPropertyTrue("spring.jpa.defer-datasource-initialization")) {
            return pass();
        }
        String ddlAuto = context.firstProperty(
                "spring.jpa.hibernate.ddl-auto",
                "spring.jpa.properties.hibernate.hbm2ddl.auto",
                "hibernate.hbm2ddl.auto");
        if (ddlAuto == null) {
            return skipped(
                    "ddl-auto is not set; for an embedded database Spring Boot defaults to create-drop, which makes defer-datasource-initialization meaningful, so this cannot be flagged without knowing the datasource.");
        }
        String normalized = ddlAuto.toLowerCase(Locale.ROOT);
        if (normalized.equals("create") || normalized.equals("create-drop") || normalized.equals("update")) {
            return pass();
        }
        return violation(
                List.of(
                        "spring.jpa.defer-datasource-initialization=true while ddl-auto=" + ddlAuto
                                + "; Hibernate does not generate the schema, so the property has no effect and data.sql must be loaded by your migration tool."));
    }
}

final class CacheAssociationCoverageRule extends AbstractHibernateRule {

    CacheAssociationCoverageRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-CACHE-001",
                        "Cached entities should also cache their associations",
                        HibernateCategory.CACHING,
                        "MEDIUM",
                        "Detects entities annotated with @Cacheable or Hibernate @Cache whose associations do not declare @Cache themselves; loading a cached aggregate then re-hits the database for every uncached association.",
                        "Annotate the associated entities (or the association attributes) with @org.hibernate.annotations.Cache so the second-level cache covers the whole graph; otherwise the cache yields little benefit.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#caching-entity"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.entities().isEmpty()) {
            return pass();
        }
        Map<String, HibernateEntityModel> byJavaType = HibernateRuleModelSupport.entitiesByJavaType(context.entities());
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            if (!entity.isJpaCacheable() && !entity.hasHibernateCacheAnnotation()) {
                continue;
            }
            for (HibernateAttributeModel attribute : entity.attributes()) {
                if (!attribute.isAssociation()) {
                    continue;
                }
                if (attribute.hasHibernateCacheAnnotation()) {
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
                        "Detects entities declaring @Cache(usage=READ_ONLY) while also declaring @Version (optimistic locking) or @DynamicUpdate, both signals that the entity is mutable.",
                        "Use READ_WRITE or NONSTRICT_READ_WRITE for mutable entities; READ_ONLY throws when Hibernate detects state changes and silently misses updates from other transactions.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#caching-entity-cache-mapping"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            String usage = entity.hibernateCacheUsageName();
            if (!"READ_ONLY".equals(usage)) {
                continue;
            }
            if (entity.hasVersionAttribute() || entity.hasDynamicUpdate()) {
                details.add(
                        entity.name()
                                + " uses @Cache(usage=READ_ONLY) but appears to be writable (@Version or @DynamicUpdate present).");
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
                        "Detects deployments where pagination over collection fetch joins is allowed to silently fetch the entire table into memory.",
                        "Enable hibernate.query.fail_on_pagination_over_collection_fetch=true to throw an exception instead of risking an OutOfMemoryError.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#collections-fetching"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.hasHibernateCollectionFetchPaginationFix()) {
            return skipped(
                    "Hibernate "
                            + context.hibernateVersionDisplay()
                            + " is 7.4 or newer, where pagination over a collection JOIN FETCH is handled in memory with a warning instead of the legacy silent full-table fetch.");
        }
        if (context.isPropertyTrue(
                "spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch",
                "hibernate.query.fail_on_pagination_over_collection_fetch")) {
            return pass();
        }
        List<String> riskyQueries = HibernateRuleModelSupport.paginatedCollectionFetchFindings(context);
        List<String> details = new ArrayList<>();
        if (riskyQueries.isEmpty()) {
            details.add(
                    "hibernate.query.fail_on_pagination_over_collection_fetch is not enabled; enable it as a safety net so a future paginated collection JOIN FETCH fails fast instead of fetching the whole table into memory.");
            return violation(HibernateRuleSupport.INFO, details);
        }
        details.add(
                "hibernate.query.fail_on_pagination_over_collection_fetch is not enabled while paginated collection JOIN FETCH queries exist.");
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
                        "Detects hibernate.format_sql=true while a production profile is active.",
                        "Disable hibernate.format_sql in production profiles to save CPU and memory, as formatting occurs even when SQL logging is off.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#configurations-logging"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (!context.isProductionProfileActive()) {
            return pass();
        }
        if (context.isPropertyTrue("spring.jpa.properties.hibernate.format_sql", "hibernate.format_sql")) {
            return violation(List.of("hibernate.format_sql is enabled while a production profile is active."));
        }
        return pass();
    }
}

final class NonOwningOneToOneEnhancementRule extends AbstractHibernateRule {

    NonOwningOneToOneEnhancementRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-MAP-018",
                        "Non-owning @OneToOne triggers N+1 queries",
                        HibernateCategory.MAPPING,
                        "HIGH",
                        "Detects non-owning (mappedBy) @OneToOne associations. Without bytecode enhancement, Hibernate cannot proxy these and fetches them eagerly.",
                        "Enable bytecode enhancement, or replace the bidirectional @OneToOne with a shared primary key (@MapsId) and a unidirectional mapping.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#BytecodeEnhancement-lazy-loading"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.isHibernateEnhancementEnabled()) {
            return pass();
        }
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
                java.lang.annotation.Annotation oneToOne = attribute.oneToOneAnnotation();
                if (oneToOne == null) {
                    continue;
                }
                String mappedBy = attribute.annotationStringValue(oneToOne, "mappedBy");
                if (mappedBy != null && !mappedBy.isBlank()) {
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
                "Detects owning foreign key associations whose join column is not the leading column of any @Index declared on the entity's @Table mapping.",
                "Declare the foreign key column as the leading column of an @Index in @Table so the schema generator creates it; if you manage schema with Flyway/Liquibase, make sure the migration creates the index. Unindexed foreign keys slow down joins and parent deletes.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html"));
    }

    // Optional JPA type: compare by class name instead of hard-referencing a class that may be absent at runtime.
    @SuppressWarnings("java:S1872")
    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            Set<String> leadingIndexColumns;
            try {
                leadingIndexColumns = leadingIndexColumns(entity.javaType());
            } catch (RuntimeException ex) {
                unresolved.add(entity.name() + " (@Table index metadata could not be resolved)");
                continue;
            }
            for (HibernateAttributeModel attribute : entity.attributes()) {
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
            columns.add(normalizeColumn(name, attribute.name()));
            return columns;
        }
        Annotation joinColumns = attribute.annotation("jakarta.persistence.JoinColumns");
        if (joinColumns != null) {
            for (String name : joinColumnsNames(joinColumns)) {
                columns.add(normalizeColumn(name, attribute.name()));
            }
            if (!columns.isEmpty()) {
                return columns;
            }
        }
        columns.add(snakeCase(attribute.name()) + "_id");
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

    private String normalizeColumn(String declaredName, String attributeName) {
        if (declaredName != null && !declaredName.isBlank()) {
            return declaredName.trim().toLowerCase(Locale.ROOT);
        }
        return snakeCase(attributeName) + "_id";
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
                        String first = columnList.split(",")[0].trim().toLowerCase(Locale.ROOT);
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

    private String snakeCase(String name) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (builder.length() > 0) {
                    builder.append('_');
                }
                builder.append(Character.toLowerCase(ch));
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }
}

final class PrimitiveIdentifierOrVersionRule extends AbstractHibernateRule {

    PrimitiveIdentifierOrVersionRule() {
        super(new HibernateRuleDefinition(
                "HIB-ENTITY-006",
                "Avoid primitive @Id or @Version types",
                HibernateCategory.ENTITY_DESIGN,
                "HIGH",
                "Detects primitive types (int, long) used for identifiers or versions. The default value is 0, which breaks Spring Data's isNew() detection.",
                "Use wrapper classes (Long, Integer) so the default value is null, preventing unnecessary SELECT statements before inserts.",
                "https://docs.spring.io/spring-data/jpa/reference/repositories/entity-state-detection.html"));
    }

    // Optional JPA/Spring Data types: compare by class name instead of hard-referencing classes that may be absent at
    // runtime.
    @SuppressWarnings("java:S1872")
    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
                boolean hasEmbeddedId = attribute.annotations().stream()
                        .anyMatch(a -> a.annotationType().getName().equals("jakarta.persistence.EmbeddedId"));
                if ((attribute.hasId() || hasEmbeddedId || attribute.hasVersion())
                        && attribute.rawType().isPrimitive()) {
                    details.add(attribute.description() + " uses primitive "
                            + attribute.rawType().getName() + " which breaks Spring Data isNew() checks.");
                }
            }
        }
        return violation(details);
    }
}

final class AssignedIdPersistableRule extends AbstractHibernateRule {

    AssignedIdPersistableRule() {
        super(new HibernateRuleDefinition(
                "HIB-ENTITY-007",
                "Assigned IDs should implement Persistable",
                HibernateCategory.ENTITY_DESIGN,
                "MEDIUM",
                "Detects entities with assigned identifiers and no @Version that do not implement org.springframework.data.domain.Persistable.",
                "Implement Persistable<ID> and manage the isNew() flag manually so Spring Data avoids a SELECT before every insert.",
                "https://docs.spring.io/spring-data/jpa/reference/repositories/entity-state-detection.html"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            boolean hasGeneratedId = entity.attributes().stream().anyMatch(a -> a.generatedValueAnnotation() != null);
            boolean hasVersion = entity.hasVersionAttribute();
            if (hasGeneratedId || hasVersion) {
                continue;
            }

            boolean implementsPersistable = false;
            Class<?> current = entity.javaType();
            while (current != null && current != Object.class) {
                for (Class<?> iface : current.getInterfaces()) {
                    if (iface.getName().equals("org.springframework.data.domain.Persistable")) {
                        implementsPersistable = true;
                        break;
                    }
                }
                if (implementsPersistable) {
                    break;
                }
                current = current.getSuperclass();
            }

            if (!implementsPersistable) {
                details.add(entity.name()
                        + " has an assigned identifier and no @Version, but does not implement Persistable.");
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
                        "Detects Spring Data JPQL @Query methods that select whole entities (lists, pages, streams, or slices) whose domain entity declares an eager @ManyToOne/@OneToOne association the query does not JOIN FETCH. JPQL does not auto-add joins for eager to-one mappings, so Hibernate issues an extra secondary select per returned row (N+1).",
                        "JOIN FETCH the eager to-one association in the query, or map it FetchType.LAZY (see HIB-FETCH-001) and fetch it explicitly only where needed.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching-strategies"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        if (context.repositories().isEmpty()) {
            return skipped("No repository metadata was detected.");
        }
        List<String> details = new ArrayList<>();
        for (HibernateRepositoryModel repository : context.repositories()) {
            HibernateEntityModel domainEntity =
                    HibernateRuleModelSupport.entityForDomainType(context, repository.domainType());
            if (domainEntity == null) {
                continue;
            }
            List<HibernateAttributeModel> eagerToOne = eagerToOneAssociations(domainEntity);
            if (eagerToOne.isEmpty()) {
                continue;
            }
            for (HibernateRepositoryMethodModel method : repository.methods()) {
                if (method.nativeQuery() || !method.hasQuery() || !method.returnsMultiple()) {
                    continue;
                }
                if (!HibernateRuleModelSupport.selectsWholeRootEntity(method.query())) {
                    continue;
                }
                Set<String> fetched = fetchedAttributes(method.query());
                List<String> uncovered = new ArrayList<>();
                for (HibernateAttributeModel association : eagerToOne) {
                    if (!fetched.contains(association.name())) {
                        uncovered.add(association.name());
                    }
                }
                if (!uncovered.isEmpty()) {
                    details.add(method.description() + " selects whole entities but does not JOIN FETCH eager to-one "
                            + String.join(", ", uncovered) + "; Hibernate runs an extra select per row (N+1).");
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
            String fetchMode = attribute.annotationValueName(attribute.fetchAnnotation(), "value");
            if ("JOIN".equals(fetchMode) || "SUBSELECT".equals(fetchMode)) {
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
                "Detects paged or streamed Spring Data JPQL @Query methods (Pageable parameter, or Page/Slice/Stream return) that select whole entities instead of a constructor expression or interface/DTO projection.",
                "For read-mostly, paged, or streamed endpoints prefer a DTO/interface projection (select new ...(...) or a projection interface) so Hibernate hydrates only the columns the caller needs.",
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
                if (HibernateRuleModelSupport.selectsWholeRootEntity(method.query())) {
                    details.add(
                            method.description()
                                    + " returns whole entities from a paged/streamed @Query; consider a DTO/interface projection.");
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
                        "Detects mutable mapped entities (entities with non-identifier persistent state) that do not declare a @Version attribute and do not opt into Hibernate versionless optimistic locking or @Immutable.",
                        "Add a @Version attribute (for example a Long or Instant) so concurrent updates fail fast instead of silently overwriting each other; skip this only for append-only, read-only, or reference data.",
                        "https://jakarta.ee/specifications/persistence/3.1/apidocs/jakarta.persistence/jakarta/persistence/version"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
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
                details.add(
                        entity.name()
                                + " has mutable persistent state but no @Version field, so concurrent updates can silently overwrite one another.");
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

final class IdentityDisablesBatchingRule extends AbstractHibernateRule {

    IdentityDisablesBatchingRule() {
        super(
                new HibernateRuleDefinition(
                        "HIB-ID-006",
                        "GenerationType.IDENTITY disables JDBC batch inserts",
                        HibernateCategory.IDENTIFIERS,
                        "HIGH",
                        "Detects entities using @GeneratedValue(strategy=IDENTITY) while hibernate.jdbc.batch_size is configured; Hibernate cannot batch inserts for IDENTITY-generated keys because it must read each generated key back immediately.",
                        "Switch IDENTITY identifiers to SEQUENCE with a pooled allocationSize so Hibernate can batch inserts, or drop the JDBC batch size expectation for these entities.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#batch-session-batch"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        Integer batchSize = context.firstIntegerProperty(
                "spring.jpa.properties.hibernate.jdbc.batch_size", "hibernate.jdbc.batch_size");
        if (batchSize == null || batchSize <= 0) {
            return skipped(
                    "hibernate.jdbc.batch_size is not configured with a positive value, so there is no insert batching for IDENTITY generation to disable.");
        }
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
                Annotation generatedValue = attribute.generatedValueAnnotation();
                if (generatedValue == null) {
                    continue;
                }
                if ("IDENTITY".equals(attribute.annotationValueName(generatedValue, "strategy"))) {
                    details.add(attribute.description()
                            + " uses GenerationType.IDENTITY, so Hibernate cannot batch its inserts despite hibernate.jdbc.batch_size="
                            + batchSize + ".");
                }
            }
        }
        return violation(details);
    }
}

final class UnidirectionalOneToManyJoinColumnRule extends AbstractHibernateRule {

    UnidirectionalOneToManyJoinColumnRule() {
        super(new HibernateRuleDefinition(
                "HIB-MAP-020",
                "Unidirectional @OneToMany with @JoinColumn issues extra UPDATE statements",
                HibernateCategory.MAPPING,
                "MEDIUM",
                "Detects unidirectional @OneToMany associations that use @JoinColumn instead of mappedBy; Hibernate inserts the child rows first and then issues separate UPDATE statements to set the foreign key.",
                "Make the association bidirectional with @ManyToOne on the child and @OneToMany(mappedBy=...) on the parent so the child's foreign key is written in the INSERT. A read-only @JoinColumn(insertable=false, updatable=false) is exempt.",
                "https://vladmihalcea.com/the-best-way-to-map-a-onetomany-relationship-with-jpa-and-hibernate/"));
    }

    @Override
    HibernateRuleResultDto evaluateRule(HibernateContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
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
                details.add(
                        attribute.description()
                                + " is a unidirectional @OneToMany with @JoinColumn, so Hibernate issues extra UPDATE statements to maintain the foreign key.");
            }
        }
        return violation(details);
    }

    private boolean isReadOnlyJoinColumn(HibernateAttributeModel attribute) {
        Annotation joinColumn = attribute.joinColumnAnnotation();
        if (joinColumn == null) {
            return false;
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
                "Detects repository @Query methods that JOIN FETCH two or more collection associations from the same root entity; fetching multiple bags throws MultipleBagFetchException and fetching multiple collections multiplies the result set into a Cartesian product.",
                "Fetch at most one collection per query. Initialize the remaining collections with separate queries, @EntityGraph attribute nodes, or @BatchSize/default_batch_fetch_size, and use Set collections to avoid MultipleBagFetchException.",
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
            HibernateEntityModel domainEntity =
                    HibernateRuleModelSupport.entityForDomainType(context, repository.domainType());
            if (domainEntity == null) {
                continue;
            }
            Map<String, Boolean> collectionIsBag = new LinkedHashMap<>();
            for (HibernateAttributeModel attribute : domainEntity.collectionAttributes()) {
                collectionIsBag.put(attribute.name(), attribute.isBagAttribute());
            }
            for (HibernateRepositoryMethodModel method : repository.methods()) {
                if (method.nativeQuery() || method.query() == null) {
                    continue;
                }
                String rootAlias = HibernateRuleModelSupport.rootAlias(method.query());
                if (rootAlias == null) {
                    continue;
                }
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
                            + "), which throws MultipleBagFetchException.");
                } else {
                    collectionDetails.add(method.description() + " JOIN FETCHes multiple collections (" + joined
                            + "), producing a Cartesian product.");
                }
            }
        }
        if (!bagDetails.isEmpty()) {
            List<String> all = new ArrayList<>(bagDetails);
            all.addAll(collectionDetails);
            return violation(HibernateRuleSupport.HIGH, all);
        }
        return violation(HibernateRuleSupport.MEDIUM, collectionDetails);
    }
}
