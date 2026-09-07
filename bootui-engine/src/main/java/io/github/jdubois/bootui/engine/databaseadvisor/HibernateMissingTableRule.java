package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedSecondaryTableFacts;
import java.util.ArrayList;
import java.util.List;

/** Reviews declared relation names against complete scoped inventories, without claiming effective physical mapping. */
final class HibernateMissingTableRule extends AbstractDatabaseAdvisorRule {

    HibernateMissingTableRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-002",
                "Declared entity relation name not found in the observed schema",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Cross-references entities with an explicit @Table(name=...) and every declared "
                        + "@SecondaryTable — honoring the declared catalog/schema — against the physical "
                        + "schema's relation inventory, only when source identity and inventory are sufficiently known.",
                "Verify the persistence unit/datasource and effective physical naming strategy, then compare migrations "
                        + "with the accessible relation inventory. An annotation name is not necessarily the runtime "
                        + "physical name; this observation alone does not establish a missing runtime table.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        if (!context.hibernateAvailable()) {
            return skipped("No EntityManagerFactory/Hibernate metamodel is available to cross-reference.");
        }
        List<SchemaSnapshot> schemas = context.availableSchemas();
        if (schemas.isEmpty()) {
            return skipped("No physical schema could be read to cross-reference against.");
        }
        List<MappedEntityFacts> targeted = context.hibernateEntities().stream()
                .filter(entity -> entity.explicitTableName() != null
                        && !entity.explicitTableName().isBlank())
                .toList();
        if (targeted.isEmpty()) {
            return skipped("No explicit table declarations with supported placement were found.");
        }
        if (context.schemas().size() != 1) {
            unknown(context, "The mapping facts do not associate persistence units with the discovered datasources.");
            return skipped("Multiple datasource inventories cannot be attributed to these mapping declarations.");
        }
        if (schemas.stream().anyMatch(SchemaSnapshot::truncated)) {
            // A truncated table list would make every unread table look like a missing one.
            unknown(context, "A truncated relation inventory cannot prove that a declared relation is absent.");
            return skipped("The table list was truncated for at least one datasource, so a missing mapped table "
                    + "cannot be distinguished from an unread one.");
        }
        List<String> details = new ArrayList<>();
        int eligible = 0;
        for (MappedEntityFacts entity : targeted) {
            MappedTableResolution resolution = MappedTableResolution.resolve(context, entity);
            if (resolution.status() == MappedTableResolution.Status.NOT_FOUND) {
                eligible++;
                details.add(entity.entityName() + " declares relation name " + entity.qualifiedTableName()
                        + ", which was not found in the scoped observed relation inventory. "
                        + "Confirm effective physical naming before changing the mapping.");
            } else if (resolution.resolved()) {
                eligible++;
            } else {
                if (resolution.status() != MappedTableResolution.Status.NOT_MAPPED) {
                    unknown(context, entity.entityName() + ": declared relation or datasource identity is unresolved.");
                }
                continue;
            }
            for (MappedSecondaryTableFacts secondaryTable : entity.secondaryTables()) {
                MappedTableResolution secondaryResolution =
                        MappedTableResolution.resolveSecondary(context, entity, secondaryTable.name());
                if (secondaryResolution.status() == MappedTableResolution.Status.NOT_FOUND) {
                    eligible++;
                    details.add(entity.entityName() + " declares @SecondaryTable " + secondaryTable.qualifiedName()
                            + ", whose name was not found in the scoped observed relation inventory.");
                } else if (secondaryResolution.resolved()) {
                    eligible++;
                } else {
                    unknown(context, entity.entityName() + ": secondary relation resolution is uncertain.");
                }
            }
        }
        return assessed(context, eligible, details);
    }
}
