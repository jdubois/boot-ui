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

final class LobLazyFetchRule extends AbstractHibernateAdvisorRule {

    LobLazyFetchRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-FETCH-005",
                        "@Lob attributes should be loaded lazily",
                        HibernateAdvisorCategory.FETCHING,
                        "MEDIUM",
                        "Detects @Lob attributes that do not declare @Basic(fetch=LAZY), so they are loaded with every entity hydration.",
                        "Annotate @Lob fields with @Basic(fetch = FetchType.LAZY) so large CLOB/BLOB payloads load only when accessed; bytecode enhancement is required for non-association lazy loading to actually defer the SQL.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#basic-binary"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class ElementCollectionEagerFetchRule extends AbstractHibernateAdvisorRule {

    ElementCollectionEagerFetchRule() {
        super(new HibernateAdvisorRuleDefinition(
                "HIB-FETCH-006",
                "@ElementCollection should default to LAZY fetching",
                HibernateAdvisorCategory.FETCHING,
                "MEDIUM",
                "Detects @ElementCollection attributes that explicitly opt into EAGER fetching.",
                "Leave @ElementCollection at the default LAZY fetch type and load values explicitly when needed; eager element collections are loaded for every entity hydration.",
                "https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1.html#a3160"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            for (HibernateAttributeModel attribute : entity.attributes()) {
                Annotation elementCollection = attribute.elementCollectionAnnotation();
                if (elementCollection == null) {
                    continue;
                }
                String fetch = attribute.annotationValueName(elementCollection, "fetch");
                if ("EAGER".equals(fetch)) {
                    details.add(attribute.description() + " is an @ElementCollection mapped as FetchType.EAGER.");
                }
            }
        }
        return violation(details);
    }
}

final class CollectionFetchJoinAnnotationRule extends AbstractHibernateAdvisorRule {

    CollectionFetchJoinAnnotationRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-FETCH-007",
                        "Collection associations should not declare @Fetch(JOIN)",
                        HibernateAdvisorCategory.FETCHING,
                        "MEDIUM",
                        "Detects collection-valued associations annotated with @Fetch(FetchMode.JOIN), which forces every fetch path through a SQL JOIN and undermines pagination.",
                        "Prefer @Fetch(FetchMode.SELECT) or SUBSELECT for collections and request JOIN FETCH only on the specific query that needs the graph.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching-strategies"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class GeneratedValueWithoutStrategyRule extends AbstractHibernateAdvisorRule {

    GeneratedValueWithoutStrategyRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-ID-004",
                        "@GeneratedValue should declare an explicit strategy",
                        HibernateAdvisorCategory.IDENTIFIERS,
                        "MEDIUM",
                        "Detects @GeneratedValue without an explicit strategy, which defaults to AUTO and typically resolves to IDENTITY on databases like MySQL and PostgreSQL.",
                        "Pick the strategy that fits the database (SEQUENCE with allocationSize on Postgres/Oracle, IDENTITY only when truly required) and set it explicitly so the choice is reviewable.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#identifiers-generators-generated-value"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class UuidIdentifierGeneratorRule extends AbstractHibernateAdvisorRule {

    UuidIdentifierGeneratorRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-ID-005",
                        "UUID identifiers should use @UuidGenerator",
                        HibernateAdvisorCategory.IDENTIFIERS,
                        "LOW",
                        "Detects UUID identifiers that rely on @GeneratedValue without the Hibernate @UuidGenerator strategy.",
                        "Annotate UUID identifiers with @UuidGenerator (TIME for index-friendly v6/v7-style values) instead of inheriting the JPA default, which yields random v4 UUIDs that fragment B-tree indexes.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#identifiers-generators-uuid"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class ElementCollectionListOrderRule extends AbstractHibernateAdvisorRule {

    ElementCollectionListOrderRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-MAP-010",
                        "@ElementCollection List should persist order",
                        HibernateAdvisorCategory.MAPPING,
                        "MEDIUM",
                        "Detects @ElementCollection List attributes that do not declare @OrderColumn or @OrderBy, so Hibernate treats every change as a delete-and-reinsert.",
                        "Add @OrderColumn for index-tracked lists or @OrderBy for query-time ordering; otherwise prefer Set<> or be aware that mutations rewrite the entire collection table.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#collections-list"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class FinalEntityRule extends AbstractHibernateAdvisorRule {

    FinalEntityRule() {
        super(new HibernateAdvisorRuleDefinition(
                "HIB-MAP-011",
                "Entity classes should not be final",
                HibernateAdvisorCategory.MAPPING,
                "HIGH",
                "Detects @Entity classes declared as final, which prevents Hibernate from creating runtime proxies for lazy associations.",
                "Remove the final modifier from entities (and avoid Kotlin classes without `open`) so lazy to-one associations and bytecode enhancement work correctly.",
                "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#entity"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            if (entity.isFinalClass()) {
                details.add(entity.name() + " is declared final.");
            }
        }
        return violation(details);
    }
}

final class SingleTableMissingDiscriminatorRule extends AbstractHibernateAdvisorRule {

    SingleTableMissingDiscriminatorRule() {
        super(new HibernateAdvisorRuleDefinition(
                "HIB-MAP-012",
                "SINGLE_TABLE inheritance should declare @DiscriminatorColumn",
                HibernateAdvisorCategory.MAPPING,
                "INFO",
                "Detects @Inheritance(SINGLE_TABLE) roots without an explicit @DiscriminatorColumn, leaving the default name and length implicit.",
                "Declare @DiscriminatorColumn (with name, type, and length) on the SINGLE_TABLE root so schema generation and reviews see the chosen contract instead of provider defaults.",
                "https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1.html#a3158"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            if ("SINGLE_TABLE".equals(entity.inheritanceStrategy()) && !entity.hasDiscriminatorColumn()) {
                details.add(entity.name() + " uses SINGLE_TABLE inheritance without @DiscriminatorColumn.");
            }
        }
        return violation(details);
    }
}

final class StringColumnLengthRule extends AbstractHibernateAdvisorRule {

    StringColumnLengthRule() {
        super(new HibernateAdvisorRuleDefinition(
                "HIB-MAP-013",
                "String columns should declare explicit length",
                HibernateAdvisorCategory.MAPPING,
                "INFO",
                "Detects persistent String attributes without @Column(length=...), which defaults to 255 in generated DDL.",
                "Set @Column(length=...) to a value that matches the domain so generated DDL, validation, and database constraints stay aligned; consider @Lob or columnDefinition for free-text payloads.",
                "https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1.html#a2128"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class BigDecimalPrecisionRule extends AbstractHibernateAdvisorRule {

    BigDecimalPrecisionRule() {
        super(new HibernateAdvisorRuleDefinition(
                "HIB-MAP-014",
                "BigDecimal columns should declare precision and scale",
                HibernateAdvisorCategory.MAPPING,
                "MEDIUM",
                "Detects BigDecimal attributes without @Column(precision=..., scale=...), which falls back to provider defaults that vary by database.",
                "Always set precision and scale on monetary or numeric @Column mappings so DDL generation and Bean Validation agree on rounding behavior.",
                "https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1.html#a2128"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class LegacyDateTimeRule extends AbstractHibernateAdvisorRule {

    LegacyDateTimeRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-MAP-015",
                        "Date/time attributes should use java.time",
                        HibernateAdvisorCategory.MAPPING,
                        "LOW",
                        "Detects persistent attributes typed as java.util.Date, java.util.Calendar, or java.sql temporal types instead of java.time.",
                        "Migrate temporal fields to java.time (Instant, LocalDate, LocalDateTime, OffsetDateTime, ZonedDateTime) so JDBC binding is immutable, time-zone aware, and free of @Temporal boilerplate.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#basic-mapping-temporal"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class ManyToOneOptionalRule extends AbstractHibernateAdvisorRule {

    ManyToOneOptionalRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-MAP-016",
                        "@ManyToOne should set optional=false when the join column is non-nullable",
                        HibernateAdvisorCategory.MAPPING,
                        "LOW",
                        "Detects @ManyToOne associations whose @JoinColumn is non-nullable but whose mapping still allows optional=true (the default).",
                        "Set @ManyToOne(optional=false) when the foreign key is mandatory so Hibernate can avoid the secondary SELECT used to discriminate between null and a real proxy.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#associations-many-to-one"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class LazyOneToOneEnhancementRule extends AbstractHibernateAdvisorRule {

    LazyOneToOneEnhancementRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-MAP-017",
                        "Lazy owning @OneToOne requires bytecode enhancement",
                        HibernateAdvisorCategory.MAPPING,
                        "INFO",
                        "Detects optional owning @OneToOne associations declared LAZY without Hibernate bytecode enhancement enabled, so Hibernate silently fetches them eagerly.",
                        "Enable hibernate.bytecode.enhancer.enableLazyInitialization (and configure the enhancement plugin), or switch the relation to @MapsId so the existing foreign key drives loading.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#BytecodeEnhancement-lazy-loading"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class EqualsHashCodeAssociationsRule extends AbstractHibernateAdvisorRule {

    EqualsHashCodeAssociationsRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-ENTITY-003",
                        "equals/hashCode should not include lazy associations",
                        HibernateAdvisorCategory.ENTITY_DESIGN,
                        "INFO",
                        "Detects entities that override equals and hashCode while exposing JPA associations. Generated implementations (Lombok @Data/@EqualsAndHashCode without exclusions, IDE templates) typically include those associations and trigger lazy loads when entities are stored in collections.",
                        "Base equals/hashCode on a stable business key or natural id only. If associations must participate, exclude lazy ones explicitly and use the entity class to avoid proxy mismatches.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#mapping-model-pojo-equalshashcode"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class ToStringAssociationsRule extends AbstractHibernateAdvisorRule {

    ToStringAssociationsRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-ENTITY-004",
                        "toString should not include lazy associations",
                        HibernateAdvisorCategory.ENTITY_DESIGN,
                        "INFO",
                        "Detects entities that override toString while exposing JPA associations. Generated implementations (Lombok @Data/@ToString without exclusions, IDE templates) typically traverse associations and trigger N+1 lazy loads or LazyInitializationException outside an open session.",
                        "Base toString on the identifier and a few stable scalar fields. Exclude associations explicitly (for example with @ToString(exclude=...)) so logging or debugging does not pull the object graph.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#mapping-model-pojo-tostring"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class PublicPersistentFieldRule extends AbstractHibernateAdvisorRule {

    PublicPersistentFieldRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-ENTITY-005",
                        "Persistent fields should not be public",
                        HibernateAdvisorCategory.ENTITY_DESIGN,
                        "LOW",
                        "Detects entity attributes that are reachable as public fields, which lets callers bypass Hibernate's instrumentation for lazy loading and dirty tracking.",
                        "Keep persistent fields private (or package-private) and expose mutators when needed; this preserves proxy substitution and bytecode-enhancer guarantees.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#entity-pojo-accessors"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class ModifyingClearAutomaticallyRule extends AbstractHibernateAdvisorRule {

    ModifyingClearAutomaticallyRule() {
        super(new HibernateAdvisorRuleDefinition(
                "HIB-QUERY-001",
                "@Modifying queries should clear or flush the persistence context",
                HibernateAdvisorCategory.QUERY,
                "HIGH",
                "Detects Spring Data @Modifying queries that do not set clearAutomatically or flushAutomatically, so the persistence context can hold stale entities after the bulk update or delete.",
                "Set @Modifying(clearAutomatically=true) (and flushAutomatically=true when pending changes must be applied first), or evict affected entities before issuing the bulk statement.",
                "https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.modifying-queries"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        if (context.repositories().isEmpty()) {
            return skipped("No Spring Data repository metadata was detected.");
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

final class StreamReturningMethodRule extends AbstractHibernateAdvisorRule {

    StreamReturningMethodRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-QUERY-002",
                        "Streaming repository methods need a transactional, read-only scope",
                        HibernateAdvisorCategory.QUERY,
                        "MEDIUM",
                        "Detects Spring Data repository methods that return java.util.stream.Stream. They keep the underlying JDBC cursor open and must run inside an open transaction with the caller closing the stream.",
                        "Wrap the caller in @Transactional(readOnly=true), consume the stream inside a try-with-resources block, and close it before the transaction ends; otherwise prefer Page<> or a bounded List<>.",
                        "https://docs.spring.io/spring-data/jpa/reference/repositories/query-methods-details.html#repositories.query-streaming"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        if (context.repositories().isEmpty()) {
            return skipped("No Spring Data repository metadata was detected.");
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

final class NativePagedQueryCountRule extends AbstractHibernateAdvisorRule {

    NativePagedQueryCountRule() {
        super(new HibernateAdvisorRuleDefinition(
                "HIB-QUERY-003",
                "Native paged @Query must declare countQuery",
                HibernateAdvisorCategory.QUERY,
                "HIGH",
                "Detects native @Query methods with a Pageable parameter or Page<> return type that do not declare countQuery, leaving Spring Data unable to derive a correct COUNT statement.",
                "Add countQuery=... to the @Query so paging can compute totals; without it Spring Data either fails to start or executes a wrong COUNT.",
                "https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.query-methods.at-query"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        if (context.repositories().isEmpty()) {
            return skipped("No Spring Data repository metadata was detected.");
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

final class DerivedDeleteByQueryRule extends AbstractHibernateAdvisorRule {

    DerivedDeleteByQueryRule() {
        super(new HibernateAdvisorRuleDefinition(
                "HIB-QUERY-004",
                "Derived deleteBy methods load entities before deletion",
                HibernateAdvisorCategory.QUERY,
                "MEDIUM",
                "Detects derived deleteBy.../removeBy... repository methods. Spring Data implements them by selecting matching entities first and then deleting them one by one, which is expensive on large result sets.",
                "For bulk removals prefer an explicit @Modifying @Query(\"delete from ... where ...\") with clearAutomatically=true; reserve derived deleteBy for small or cascading deletes.",
                "https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.query-methods.modifying"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        if (context.repositories().isEmpty()) {
            return skipped("No Spring Data repository metadata was detected.");
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

final class SqlLoggingInProductionRule extends AbstractHibernateAdvisorRule {

    SqlLoggingInProductionRule() {
        super(new HibernateAdvisorRuleDefinition(
                "HIB-CONFIG-012",
                "SQL logging should be off when a production profile is active",
                HibernateAdvisorCategory.CONFIGURATION,
                "MEDIUM",
                "Detects show-sql or DEBUG/TRACE logging for Hibernate SQL/binder categories while a production-like profile (prod, production, staging) is active.",
                "Disable spring.jpa.show-sql and keep org.hibernate.SQL / org.hibernate.orm.jdbc.bind at INFO or WARN in production; logging every statement degrades throughput dramatically.",
                "https://docs.spring.io/spring-boot/reference/features/logging.html"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        if (!context.isProductionProfileActive()) {
            return pass();
        }
        if (context.isSqlLoggingEnabled()) {
            return violation(List.of("SQL logging is enabled while a production profile is active."));
        }
        return pass();
    }
}

final class JdbcTimeZoneRule extends AbstractHibernateAdvisorRule {

    JdbcTimeZoneRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-CONFIG-013",
                        "Configure hibernate.jdbc.time_zone",
                        HibernateAdvisorCategory.CONFIGURATION,
                        "LOW",
                        "Detects deployments that do not pin hibernate.jdbc.time_zone, leaving java.time conversions to use the JVM default zone.",
                        "Set spring.jpa.properties.hibernate.jdbc.time_zone=UTC (or another fixed zone) so JDBC binds/reads of timestamps are deterministic across hosts and migrations.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#basic-datetime-timezone"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        String value =
                context.firstProperty("spring.jpa.properties.hibernate.jdbc.time_zone", "hibernate.jdbc.time_zone");
        if (value == null || value.isBlank()) {
            return violation(List.of("hibernate.jdbc.time_zone is not configured."));
        }
        return pass();
    }
}

final class HibernateBuiltinPoolRule extends AbstractHibernateAdvisorRule {

    HibernateBuiltinPoolRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-CONFIG-014",
                        "Hibernate's built-in connection pool should not be used",
                        HibernateAdvisorCategory.CONFIGURATION,
                        "HIGH",
                        "Detects hibernate.connection.pool_size, which only activates Hibernate's internal connection pool, intended for testing and not for production load.",
                        "Remove hibernate.connection.pool_size and rely on Spring Boot's DataSource (HikariCP by default) so connections come from a tuned, monitored pool.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#database-connectionprovider"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        String value = context.firstProperty(
                "spring.jpa.properties.hibernate.connection.pool_size", "hibernate.connection.pool_size");
        if (value != null && !value.isBlank()) {
            return violation(List.of("hibernate.connection.pool_size is set to " + value
                    + "; switch to a managed DataSource (for example HikariCP)."));
        }
        return pass();
    }
}

final class DeferDatasourceInitializationRule extends AbstractHibernateAdvisorRule {

    DeferDatasourceInitializationRule() {
        super(new HibernateAdvisorRuleDefinition(
                "HIB-CONFIG-015",
                "spring.jpa.defer-datasource-initialization is only safe with embedded DDL flows",
                HibernateAdvisorCategory.CONFIGURATION,
                "MEDIUM",
                "Detects spring.jpa.defer-datasource-initialization=true while ddl-auto is none/validate; the setting is meaningful only when Hibernate generates the schema (create/create-drop/update).",
                "Combine defer-datasource-initialization=true with ddl-auto=create or create-drop, or remove it when relying on Flyway/Liquibase so data.sql is loaded by the migration tool instead.",
                "https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.datasource.initialization"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        if (!context.isPropertyTrue("spring.jpa.defer-datasource-initialization")) {
            return pass();
        }
        String ddlAuto = context.firstProperty(
                "spring.jpa.hibernate.ddl-auto",
                "spring.jpa.properties.hibernate.hbm2ddl.auto",
                "hibernate.hbm2ddl.auto");
        if (ddlAuto == null) {
            return violation(
                    List.of(
                            "spring.jpa.defer-datasource-initialization=true but ddl-auto is not configured; the property has no effect."));
        }
        String normalized = ddlAuto.toLowerCase(Locale.ROOT);
        if (normalized.equals("create") || normalized.equals("create-drop") || normalized.equals("update")) {
            return pass();
        }
        return violation(List.of("spring.jpa.defer-datasource-initialization=true while ddl-auto=" + ddlAuto
                + "; the property has no effect."));
    }
}

final class CacheAssociationCoverageRule extends AbstractHibernateAdvisorRule {

    CacheAssociationCoverageRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-CACHE-001",
                        "Cached entities should also cache their associations",
                        HibernateAdvisorCategory.CACHING,
                        "MEDIUM",
                        "Detects entities annotated with @Cacheable or Hibernate @Cache whose associations do not declare @Cache themselves; loading a cached aggregate then re-hits the database for every uncached association.",
                        "Annotate the associated entities (or the association attributes) with @org.hibernate.annotations.Cache so the second-level cache covers the whole graph; otherwise the cache yields little benefit.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#caching-entity"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        if (context.entities().isEmpty()) {
            return pass();
        }
        java.util.Map<String, HibernateEntityModel> byJavaType = new java.util.HashMap<>();
        for (HibernateEntityModel entity : context.entities()) {
            if (entity.javaType() != null) {
                byJavaType.put(entity.javaType().getName(), entity);
            }
        }
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
                Class<?> targetType = associationTargetType(attribute);
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

    private Class<?> associationTargetType(HibernateAttributeModel attribute) {
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

final class ReadOnlyCacheOnWritableEntityRule extends AbstractHibernateAdvisorRule {

    ReadOnlyCacheOnWritableEntityRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-CACHE-002",
                        "READ_ONLY cache strategy on writable entities is unsafe",
                        HibernateAdvisorCategory.CACHING,
                        "MEDIUM",
                        "Detects entities declaring @Cache(usage=READ_ONLY) while also declaring @Version (optimistic locking) or @DynamicUpdate, both signals that the entity is mutable.",
                        "Use READ_WRITE or NONSTRICT_READ_WRITE for mutable entities; READ_ONLY throws when Hibernate detects state changes and silently misses updates from other transactions.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#caching-entity-cache-mapping"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class FailOnPaginationOverCollectionFetchRule extends AbstractHibernateAdvisorRule {

    FailOnPaginationOverCollectionFetchRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-CONFIG-016",
                        "Fail on pagination over collection fetch",
                        HibernateAdvisorCategory.CONFIGURATION,
                        "HIGH",
                        "Detects deployments where pagination over collection fetch joins is allowed to silently fetch the entire table into memory.",
                        "Enable hibernate.query.fail_on_pagination_over_collection_fetch=true to throw an exception instead of risking an OutOfMemoryError.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#collections-fetching"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        if (!context.isPropertyTrue(
                "spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch",
                "hibernate.query.fail_on_pagination_over_collection_fetch")) {
            return violation(List.of("hibernate.query.fail_on_pagination_over_collection_fetch is not enabled."));
        }
        return pass();
    }
}

final class FormatSqlInProductionRule extends AbstractHibernateAdvisorRule {

    FormatSqlInProductionRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-CONFIG-017",
                        "Disable SQL formatting in production",
                        HibernateAdvisorCategory.CONFIGURATION,
                        "LOW",
                        "Detects hibernate.format_sql=true while a production profile is active.",
                        "Disable hibernate.format_sql in production profiles to save CPU and memory, as formatting occurs even when SQL logging is off.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#configurations-logging"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        if (!context.isProductionProfileActive()) {
            return pass();
        }
        if (context.isPropertyTrue("spring.jpa.properties.hibernate.format_sql", "hibernate.format_sql")) {
            return violation(List.of("hibernate.format_sql is enabled while a production profile is active."));
        }
        return pass();
    }
}

final class NonOwningOneToOneEnhancementRule extends AbstractHibernateAdvisorRule {

    NonOwningOneToOneEnhancementRule() {
        super(
                new HibernateAdvisorRuleDefinition(
                        "HIB-MAP-018",
                        "Non-owning @OneToOne triggers N+1 queries",
                        HibernateAdvisorCategory.MAPPING,
                        "HIGH",
                        "Detects non-owning (mappedBy) @OneToOne associations. Without bytecode enhancement, Hibernate cannot proxy these and fetches them eagerly.",
                        "Enable bytecode enhancement, or replace the bidirectional @OneToOne with a shared primary key (@MapsId) and a unidirectional mapping.",
                        "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#BytecodeEnhancement-lazy-loading"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class MissingForeignKeyIndexRule extends AbstractHibernateAdvisorRule {

    MissingForeignKeyIndexRule() {
        super(new HibernateAdvisorRuleDefinition(
                "HIB-MAP-019",
                "Missing foreign key indexes",
                HibernateAdvisorCategory.MAPPING,
                "INFO",
                "Detects foreign key associations without a corresponding @Index on the entity's @Table mapping.",
                "Declare @Index in @Table so schema generators create indexes; if you use Flyway/Liquibase, ensure the index exists in your migrations.",
                "https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1.html#a11145"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (HibernateEntityModel entity : context.entities()) {
            List<String> indexedColumns = new ArrayList<>();
            for (java.lang.annotation.Annotation ann : entity.javaType().getAnnotations()) {
                if ("jakarta.persistence.Table".equals(ann.annotationType().getName())) {
                    try {
                        java.lang.annotation.Annotation[] indexes = (java.lang.annotation.Annotation[])
                                ann.annotationType().getMethod("indexes").invoke(ann);
                        for (java.lang.annotation.Annotation index : indexes) {
                            String columnList = (String) index.annotationType()
                                    .getMethod("columnList")
                                    .invoke(index);
                            for (String col : columnList.split(",")) {
                                indexedColumns.add(col.trim().toLowerCase());
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    break;
                }
            }

            for (HibernateAttributeModel attribute : entity.attributes()) {
                if (attribute.manyToOneAnnotation() != null
                        || (attribute.oneToOneAnnotation() != null
                                && (attribute.annotationStringValue(attribute.oneToOneAnnotation(), "mappedBy") == null
                                        || attribute
                                                .annotationStringValue(attribute.oneToOneAnnotation(), "mappedBy")
                                                .isBlank()))) {
                    String name;
                    java.lang.annotation.Annotation joinColumn = attribute.joinColumnAnnotation();
                    if (joinColumn != null) {
                        name = attribute.annotationStringValue(joinColumn, "name");
                    } else {
                        name = attribute.name() + "_id";
                    }
                    if (name != null && !name.isBlank() && !indexedColumns.contains(name.toLowerCase())) {
                        details.add(attribute.description() + " is a foreign key (" + name
                                + ") but no @Index is declared on the @Table.");
                    }
                }
            }
        }
        return violation(details);
    }
}

final class PrimitiveIdentifierOrVersionRule extends AbstractHibernateAdvisorRule {

    PrimitiveIdentifierOrVersionRule() {
        super(new HibernateAdvisorRuleDefinition(
                "HIB-ENTITY-006",
                "Avoid primitive @Id or @Version types",
                HibernateAdvisorCategory.ENTITY_DESIGN,
                "HIGH",
                "Detects primitive types (int, long) used for identifiers or versions. The default value is 0, which breaks Spring Data's isNew() detection.",
                "Use wrapper classes (Long, Integer) so the default value is null, preventing unnecessary SELECT statements before inserts.",
                "https://docs.spring.io/spring-data/jpa/reference/repositories/entity-state-detection.html"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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

final class AssignedIdPersistableRule extends AbstractHibernateAdvisorRule {

    AssignedIdPersistableRule() {
        super(new HibernateAdvisorRuleDefinition(
                "HIB-ENTITY-007",
                "Assigned IDs should implement Persistable",
                HibernateAdvisorCategory.ENTITY_DESIGN,
                "MEDIUM",
                "Detects entities with assigned identifiers and no @Version that do not implement org.springframework.data.domain.Persistable.",
                "Implement Persistable<ID> and manage the isNew() flag manually so Spring Data avoids a SELECT before every insert.",
                "https://docs.spring.io/spring-data/jpa/reference/repositories/entity-state-detection.html"));
    }

    @Override
    HibernateAdvisorRuleResultDto evaluateRule(HibernateAdvisorContext context) {
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
