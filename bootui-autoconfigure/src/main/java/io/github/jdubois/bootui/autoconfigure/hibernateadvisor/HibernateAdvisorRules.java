package io.github.jdubois.bootui.autoconfigure.hibernateadvisor;

import io.github.jdubois.bootui.core.dto.HibernateAdvisorRuleResultDto;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

abstract class AbstractHibernateAdvisorRule implements HibernateAdvisorRule {

    private final HibernateAdvisorRuleDefinition definition;

    AbstractHibernateAdvisorRule(HibernateAdvisorRuleDefinition definition) {
        this.definition = definition;
    }

    @Override
    public final HibernateAdvisorRuleDefinition definition() {
        return definition;
    }

    abstract HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context);

    @Override
    public final HibernateAdvisorRuleResultDto evaluate(HibernateAdvisorContext context) {
        try {
            return evaluateRule(context);
        } catch (RuntimeException | LinkageError ex) {
            return HibernateAdvisorRuleSupport.error(definition, "Rule could not be evaluated: " + ex.getMessage());
        }
    }

    HibernateAdvisorRuleResultDto pass() {
        return HibernateAdvisorRuleSupport.pass(definition);
    }

    HibernateAdvisorRuleResultDto skipped(String reason) {
        return HibernateAdvisorRuleSupport.skipped(definition, reason);
    }

    HibernateAdvisorRuleResultDto violation(List<String> details) {
        return details.isEmpty() ? pass() : HibernateAdvisorRuleSupport.violation(definition, details);
    }
}

final class EagerAssociationFetchRule extends AbstractHibernateAdvisorRule {

    EagerAssociationFetchRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-FETCH-001",
                        "Associations should avoid eager fetching by default",
                        HibernateAdvisorCategory.FETCHING,
                        "HIGH",
                        "Detects JPA associations mapped with FetchType.EAGER, including default-eager to-one associations.",
                        "Prefer LAZY associations and fetch required graphs explicitly with joins, entity graphs, or DTO queries.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
                Annotation association = attribute.associationAnnotation();
                if (association == null) {
                    continue;
                }
                String fetch = attribute.annotationValueName(association, "fetch");
                if ("EAGER".equals(fetch)) {
                    details.add(attribute.description() + " is mapped as FetchType.EAGER.");
                }
            }
        }
        return violation(details);
    }
}

final class IdentityIdentifierRule extends AbstractHibernateAdvisorRule {

    IdentityIdentifierRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-ID-001",
                        "Generated identifiers should avoid GenerationType.IDENTITY",
                        HibernateAdvisorCategory.IDENTIFIERS,
                        "MEDIUM",
                        "Detects identifiers using GenerationType.IDENTITY, which prevents JDBC batch inserts.",
                        "Prefer SEQUENCE with allocationSize and Hibernate's pooled optimizer when the database supports sequences.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#identifiers-generators"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class TableIdentifierRule extends AbstractHibernateAdvisorRule {

    TableIdentifierRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-ID-002",
                        "Generated identifiers should avoid GenerationType.TABLE",
                        HibernateAdvisorCategory.IDENTIFIERS,
                        "HIGH",
                        "Detects identifiers using GenerationType.TABLE, which serializes id allocation through a table row.",
                        "Prefer SEQUENCE with a pooled allocation size, or IDENTITY only when the database has no sequence support.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#identifiers-generators"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class SequenceAllocationSizeRule extends AbstractHibernateAdvisorRule {

    SequenceAllocationSizeRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-ID-003",
                        "@SequenceGenerator should use pooled allocation",
                        HibernateAdvisorCategory.IDENTIFIERS,
                        "MEDIUM",
                        "Detects @SequenceGenerator declarations with allocationSize=1.",
                        "Use an allocation size greater than 1 and keep it aligned with the database sequence increment.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#identifiers-generators-sequence"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class UnidirectionalOneToManyRule extends AbstractHibernateAdvisorRule {

    UnidirectionalOneToManyRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-MAP-001",
                        "One-to-many associations should be bidirectional or join-column based",
                        HibernateAdvisorCategory.MAPPING,
                        "MEDIUM",
                        "Detects unidirectional @OneToMany mappings without mappedBy or @JoinColumn.",
                        "Use mappedBy for bidirectional ownership, or add @JoinColumn when a unidirectional one-to-many is intentional.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#associations-one-to-many"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class ManyToManyListRule extends AbstractHibernateAdvisorRule {

    ManyToManyListRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-MAP-002",
                        "Many-to-many associations should use Set semantics",
                        HibernateAdvisorCategory.MAPPING,
                        "MEDIUM",
                        "Detects @ManyToMany associations declared as List, which can trigger delete-and-reinsert DML.",
                        "Use Set for many-to-many associations, or model the join table as an entity when it has business meaning.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#associations-many-to-many"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class ManyToManyRemoveCascadeRule extends AbstractHibernateAdvisorRule {

    ManyToManyRemoveCascadeRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-MAP-004",
                        "Many-to-many associations should not cascade remove",
                        HibernateAdvisorCategory.MAPPING,
                        "HIGH",
                        "Detects @ManyToMany mappings whose cascade list contains REMOVE or ALL.",
                        "Remove REMOVE/ALL cascades from many-to-many associations; model the join table as an entity when lifecycle ownership is needed.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#pc-cascade"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class ManyToOneRemoveCascadeRule extends AbstractHibernateAdvisorRule {

    ManyToOneRemoveCascadeRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-MAP-005",
                        "Many-to-one associations should not cascade remove",
                        HibernateAdvisorCategory.MAPPING,
                        "HIGH",
                        "Detects @ManyToOne mappings whose cascade list contains REMOVE or ALL.",
                        "Remove REMOVE/ALL cascades from many-to-one associations so deletes do not propagate from children to shared parents.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#pc-cascade"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class OneToOneWithoutMapsIdRule extends AbstractHibernateAdvisorRule {

    OneToOneWithoutMapsIdRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-MAP-006",
                        "One-to-one associations should prefer shared primary keys",
                        HibernateAdvisorCategory.MAPPING,
                        "MEDIUM",
                        "Detects owning-side @OneToOne mappings that do not use @MapsId.",
                        "Use @MapsId for dependent one-to-one entities when the child row has the same lifecycle and identifier as the parent.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#identifiers-derived"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
                Annotation oneToOne = attribute.oneToOneAnnotation();
                if (oneToOne == null || attribute.hasMapsId() || attribute.hasId()) {
                    continue;
                }
                String mappedBy = attribute.annotationStringValue(oneToOne, "mappedBy");
                if (mappedBy == null || mappedBy.isBlank()) {
                    details.add(attribute.description() + " is an owning @OneToOne without @MapsId.");
                }
            }
        }
        return violation(details);
    }
}

final class TablePerClassInheritanceRule extends AbstractHibernateAdvisorRule {

    TablePerClassInheritanceRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-MAP-007",
                        "Entity inheritance should avoid TABLE_PER_CLASS",
                        HibernateAdvisorCategory.MAPPING,
                        "MEDIUM",
                        "Detects @Inheritance(strategy = TABLE_PER_CLASS), which requires UNION queries for polymorphic loads.",
                        "Prefer SINGLE_TABLE or JOINED inheritance unless every subtype is queried independently.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#entity-inheritance"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class NotFoundIgnoreRule extends AbstractHibernateAdvisorRule {

    NotFoundIgnoreRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-MAP-008",
                        "@NotFound(IGNORE) should be reviewed",
                        HibernateAdvisorCategory.MAPPING,
                        "MEDIUM",
                        "Detects Hibernate @NotFound(action = IGNORE), which hides missing references and forces eager resolution.",
                        "Fix referential integrity or model optional data explicitly instead of suppressing missing target rows.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#associations-not-found"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class OptionalPersistentAttributeRule extends AbstractHibernateAdvisorRule {

    OptionalPersistentAttributeRule() {
        super(new HibernateAdvisorRuleDefinition(
                "HIB-MAP-009",
                "Persistent attributes should not be Optional",
                HibernateAdvisorCategory.MAPPING,
                "MEDIUM",
                "Detects mapped attributes declared as java.util.Optional.",
                "Map the underlying nullable type and expose Optional from a non-persistent getter if desired.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class MultipleBagCollectionRule extends AbstractHibernateAdvisorRule {

    MultipleBagCollectionRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-FETCH-004",
                        "Entities should avoid multiple bag collections",
                        HibernateAdvisorCategory.FETCHING,
                        "MEDIUM",
                        "Detects entities with two or more unordered List/Collection associations.",
                        "Fetch at most one bag collection per query, add @OrderColumn when list order is persistent, or split loading into targeted queries.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching-strategies"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class OrdinalEnumRule extends AbstractHibernateAdvisorRule {

    OrdinalEnumRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-MAP-003",
                        "Enum attributes should be stored as strings",
                        HibernateAdvisorCategory.MAPPING,
                        "MEDIUM",
                        "Detects enum attributes that use @Enumerated(ORDINAL) or omit @Enumerated, which defaults to ORDINAL.",
                        "Use @Enumerated(EnumType.STRING) or an explicit converter so enum reordering does not corrupt data.",
                        "https://jakarta.ee/specifications/persistence/3.2/apidocs/jakarta.persistence/jakarta/persistence/enumerated"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
                if (!attribute.isEnumAttribute()) {
                    continue;
                }
                Annotation enumerated = attribute.enumeratedAnnotation();
                String value = enumerated == null ? "ORDINAL" : attribute.annotationValueName(enumerated, "value");
                if (!"STRING".equals(value)) {
                    details.add(attribute.description() + " stores enum values as ORDINAL"
                            + (enumerated == null ? " by JPA default." : "."));
                }
            }
        }
        return violation(details);
    }
}

final class OpenInViewRule extends AbstractHibernateAdvisorRule {

    OpenInViewRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-CONFIG-001",
                        "Open Session in View should be disabled",
                        HibernateAdvisorCategory.CONFIGURATION,
                        "MEDIUM",
                        "Detects spring.jpa.open-in-view=true, including Spring Boot's default when the property is not set.",
                        "Set spring.jpa.open-in-view=false and fetch data inside transactional service boundaries.",
                        "https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.jpa-and-spring-data.open-in-view"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        Boolean value = context.environment().getProperty("spring.jpa.open-in-view", Boolean.class);
        if (Boolean.FALSE.equals(value)) {
            return pass();
        }
        String detail = value == null
                ? "spring.jpa.open-in-view is not set, so Spring Boot's web default enables it."
                : "spring.jpa.open-in-view=true is enabled.";
        return violation(List.of(detail));
    }
}

final class MissingBatchFetchRule extends AbstractHibernateAdvisorRule {

    MissingBatchFetchRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-FETCH-002",
                        "Batch fetching should be configured for association-heavy models",
                        HibernateAdvisorCategory.FETCHING,
                        "INFO",
                        "Detects mapped associations with no global hibernate.default_batch_fetch_size and no @BatchSize.",
                        "Set a bounded hibernate.default_batch_fetch_size or add @BatchSize to high-traffic associations to reduce N+1 select risk.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching-batch"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        if (!context.hasAssociations()) {
            return skipped("No mapped associations were detected.");
        }
        if (context.defaultBatchFetchSize() != null || context.hasBatchSizeAnnotation()) {
            return pass();
        }
        return HibernateAdvisorRuleSupport.result(
                definition(),
                HibernateAdvisorRuleSupport.VIOLATION,
                context.associationCount(),
                List.of(context.associationCount()
                        + " mapped association(s) were detected without a global batch-fetch size or @BatchSize."));
    }
}

final class CollectionJoinFetchPageableRule extends AbstractHibernateAdvisorRule {

    private static final Pattern FROM_ALIAS =
            Pattern.compile("\\bfrom\\s+[\\w.$]+\\s+(\\w+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern JOIN_FETCH =
            Pattern.compile("\\bjoin\\s+fetch\\s+([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)+)\\b", Pattern.CASE_INSENSITIVE);

    CollectionJoinFetchPageableRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-FETCH-003",
                        "Collection fetch joins should not be paged directly",
                        HibernateAdvisorCategory.FETCHING,
                        "HIGH",
                        "Detects Spring Data JPQL queries that combine Pageable with a collection JOIN FETCH.",
                        "Page root ids first, then fetch the required collection graph in a second query inside the same transaction.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#hql-fetching"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        if (context.repositories().isEmpty()) {
            return skipped("No Spring Data repository metadata was detected.");
        }
        List<String> details = new ArrayList<>();
        for (HibernateRepositoryModel repository : context.repositories()) {
            HibernateEntityModel domainEntity = entityForDomain(context, repository.domainType());
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
        return violation(details);
    }

    private HibernateEntityModel entityForDomain(HibernateAdvisorContext context, Class<?> domainType) {
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

    private Set<String> collectionAttributeNames(HibernateEntityModel entity) {
        Set<String> names = new HashSet<>();
        for (HibernateAttributeModel attribute : entity.collectionAttributes()) {
            names.add(attribute.name());
        }
        return names;
    }

    private String rootAlias(String query) {
        Matcher matcher = FROM_ALIAS.matcher(query);
        return matcher.find() ? matcher.group(1) : null;
    }

    private List<String> joinFetchPaths(String query) {
        List<String> paths = new ArrayList<>();
        Matcher matcher = JOIN_FETCH.matcher(query);
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
        return paths;
    }

    private String directAttribute(String rootAlias, String path) {
        String prefix = rootAlias + ".";
        if (!path.startsWith(prefix)) {
            return null;
        }
        String remainder = path.substring(prefix.length());
        int dot = remainder.indexOf('.');
        return dot == -1 ? remainder : remainder.substring(0, dot);
    }
}

final class LazyLoadNoTransRule extends AbstractHibernateAdvisorRule {

    LazyLoadNoTransRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-CONFIG-003",
                        "Lazy loading outside transactions should stay disabled",
                        HibernateAdvisorCategory.CONFIGURATION,
                        "HIGH",
                        "Detects hibernate.enable_lazy_load_no_trans=true.",
                        "Remove this setting and fetch required data inside transaction boundaries with explicit fetch plans or DTO queries.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        if (context.isPropertyTrue(
                "spring.jpa.properties.hibernate.enable_lazy_load_no_trans", "hibernate.enable_lazy_load_no_trans")) {
            return violation(List.of("hibernate.enable_lazy_load_no_trans=true is enabled."));
        }
        return pass();
    }
}

final class JdbcBatchSizeRule extends AbstractHibernateAdvisorRule {

    JdbcBatchSizeRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-CONFIG-004",
                        "JDBC batching should be configured for writes",
                        HibernateAdvisorCategory.CONFIGURATION,
                        "INFO",
                        "Detects missing or non-positive hibernate.jdbc.batch_size.",
                        "Set a bounded JDBC batch size such as 25 for write-capable applications, then tune it with representative workloads.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#batch-session-batch"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        Integer batchSize = context.firstIntegerProperty(
                "spring.jpa.properties.hibernate.jdbc.batch_size", "hibernate.jdbc.batch_size");
        if (batchSize != null && batchSize > 0) {
            return pass();
        }
        return violation(List.of("hibernate.jdbc.batch_size is not configured with a positive value."));
    }
}

final class OrderedBatchingRule extends AbstractHibernateAdvisorRule {

    OrderedBatchingRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-CONFIG-005",
                        "JDBC batching should order inserts and updates",
                        HibernateAdvisorCategory.CONFIGURATION,
                        "INFO",
                        "Detects configured JDBC batching without hibernate.order_inserts and hibernate.order_updates.",
                        "Enable order_inserts and order_updates so same-table statements are grouped into larger JDBC batches.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#batch-session-batch"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class SlowQueryLogRule extends AbstractHibernateAdvisorRule {

    SlowQueryLogRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-CONFIG-006",
                        "Slow query logging should be available in development",
                        HibernateAdvisorCategory.CONFIGURATION,
                        "INFO",
                        "Detects missing Hibernate slow-query threshold configuration.",
                        "Configure a bounded slow-query threshold in development and staging profiles to surface expensive SQL early.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#statistics"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class HibernateStatisticsRule extends AbstractHibernateAdvisorRule {

    HibernateStatisticsRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-CONFIG-007",
                        "Hibernate statistics should be enabled when tuning",
                        HibernateAdvisorCategory.CONFIGURATION,
                        "INFO",
                        "Detects hibernate.generate_statistics not being enabled for the current environment.",
                        "Enable statistics in development or performance-test profiles when investigating query counts, cache efficiency, and fetch plans.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#statistics"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        if (context.isPropertyTrue(
                "spring.jpa.properties.hibernate.generate_statistics", "hibernate.generate_statistics")) {
            return pass();
        }
        return violation(List.of("hibernate.generate_statistics is not enabled."));
    }
}

final class ProviderDisablesAutocommitRule extends AbstractHibernateAdvisorRule {

    ProviderDisablesAutocommitRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-CONFIG-008",
                        "Connection providers should disable auto-commit explicitly",
                        HibernateAdvisorCategory.CONFIGURATION,
                        "INFO",
                        "Detects resource-local configurations where hibernate.connection.provider_disables_autocommit is not enabled.",
                        "When the connection pool disables auto-commit, set hibernate.connection.provider_disables_autocommit=true so Hibernate can delay connection acquisition.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#database-connectionprovider"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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
        return violation(List.of("hibernate.connection.provider_disables_autocommit is not enabled."));
    }
}

final class InClausePaddingRule extends AbstractHibernateAdvisorRule {

    InClausePaddingRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-CONFIG-009",
                        "Collection-parameter queries should use IN-clause padding",
                        HibernateAdvisorCategory.CONFIGURATION,
                        "INFO",
                        "Detects repository queries with collection parameters when hibernate.query.in_clause_parameter_padding is disabled.",
                        "Enable IN-clause parameter padding when variable-length IN predicates are common and the database benefits from plan reuse.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#configurations-query"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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
                if (method.query().toLowerCase(Locale.ROOT).contains(" in ")) {
                    details.add(method.description() + " has a collection parameter in an IN predicate.");
                }
            }
        }
        return violation(details);
    }
}

final class QueryCacheRegionFactoryRule extends AbstractHibernateAdvisorRule {

    QueryCacheRegionFactoryRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-CONFIG-010",
                        "Query cache requires a second-level cache provider",
                        HibernateAdvisorCategory.CONFIGURATION,
                        "HIGH",
                        "Detects hibernate.cache.use_query_cache=true without a second-level cache provider.",
                        "Disable query caching or configure a second-level cache region factory and cache the entities returned by cacheable entity queries.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#caching-query"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class CacheableWithoutCacheStrategyRule extends AbstractHibernateAdvisorRule {

    CacheableWithoutCacheStrategyRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-CONFIG-011",
                        "Cacheable entities should declare an explicit cache strategy",
                        HibernateAdvisorCategory.CONFIGURATION,
                        "MEDIUM",
                        "Detects JPA @Cacheable entities without Hibernate @Cache when second-level caching appears configured.",
                        "Add an explicit Hibernate cache concurrency strategy or remove @Cacheable if the entity should not use the second-level cache.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#caching"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class RiskyDdlAutoRule extends AbstractHibernateAdvisorRule {

    private static final List<String> RISKY_VALUES = List.of("update", "create", "create-drop");

    RiskyDdlAutoRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-CONFIG-002",
                        "Schema generation should not mutate non-test databases",
                        HibernateAdvisorCategory.CONFIGURATION,
                        "INFO",
                        "Detects ddl-auto values that update, create, or drop schemas outside test profiles.",
                        "Use versioned migrations for shared databases and reserve ddl-auto=create/create-drop/update for disposable test environments.",
                        "https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.jpa-and-spring-data.creating-and-dropping"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        String ddlAuto = context.firstProperty(
                "spring.jpa.hibernate.ddl-auto",
                "spring.jpa.properties.hibernate.hbm2ddl.auto",
                "hibernate.hbm2ddl.auto");
        if (ddlAuto == null) {
            return pass();
        }
        String normalized = ddlAuto.toLowerCase(Locale.ROOT);
        if (!RISKY_VALUES.contains(normalized)
                || hasTestProfile(context.environment().getActiveProfiles())) {
            return pass();
        }
        return violation(List.of("ddl-auto is set to " + ddlAuto + " outside a test profile."));
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
}

final class EqualsHashCodePairRule extends AbstractHibernateAdvisorRule {

    EqualsHashCodePairRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-ENTITY-001",
                        "Entities should override equals and hashCode consistently",
                        HibernateAdvisorCategory.ENTITY_DESIGN,
                        "INFO",
                        "Detects entities that override equals without hashCode, or hashCode without equals.",
                        "Implement equals and hashCode as a pair, and review generated identifier semantics before using entities in sets or maps.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#mapping-model-pojo-equalshashcode"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class OptimisticLockingDynamicUpdateRule extends AbstractHibernateAdvisorRule {

    OptimisticLockingDynamicUpdateRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-ENTITY-002",
                        "Versionless optimistic locking should use dynamic updates",
                        HibernateAdvisorCategory.ENTITY_DESIGN,
                        "MEDIUM",
                        "Detects Hibernate @OptimisticLocking(DIRTY/ALL) without @DynamicUpdate.",
                        "Add @DynamicUpdate when using versionless optimistic locking so UPDATE statements include the intended changed columns.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#locking-optimistic-versionless"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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
