package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-references mapped entities that declare an explicit {@code @Table(name=...)} against the physical
 * schema: a mapped table that is simply absent from the database usually points to a stale entity, a
 * missing migration, or the wrong datasource/persistence-unit wiring. Entities relying on the default
 * naming strategy are not evaluated (their physical name is not guessed), keeping this low-false-positive.
 */
final class HibernateMissingTableRule extends AbstractDatabaseAdvisorRule {

    HibernateMissingTableRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-002",
                "Mapped entity table not found in the physical schema",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Cross-references entities with an explicit @Table(name=...) against the physical schema's table "
                        + "names.",
                "Verify the entity is mapped to the correct persistence unit/datasource, that a pending migration "
                        + "creates the table, or that the entity is stale and should be removed.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        if (!context.hibernateAvailable()) {
            return skipped("No EntityManagerFactory/Hibernate metamodel is available to cross-reference.");
        }
        if (context.availableSchemas().isEmpty()) {
            return skipped("No physical schema could be read to cross-reference against.");
        }
        List<String> details = new ArrayList<>();
        for (MappedEntityFacts entity : context.hibernateEntities()) {
            if (entity.explicitTableName() == null) {
                continue;
            }
            boolean found = context.availableSchemas().stream()
                    .anyMatch(schema -> schema.table(entity.explicitTableName()) != null);
            if (!found) {
                details.add(entity.entityName() + " is mapped to table " + entity.explicitTableName()
                        + ", which was not found in the physical schema.");
            }
        }
        return violation(details);
    }
}
