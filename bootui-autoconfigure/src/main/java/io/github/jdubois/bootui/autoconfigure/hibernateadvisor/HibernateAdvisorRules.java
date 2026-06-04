package io.github.jdubois.bootui.autoconfigure.hibernateadvisor;

import io.github.jdubois.bootui.core.dto.HibernateAdvisorRuleResultDto;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
