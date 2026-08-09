package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedForeignKeyFacts;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-references the Hibernate metamodel against the physical schema: a mapped {@code @ManyToOne}/
 * {@code @OneToOne} foreign key column with no supporting physical index. Unlike the Hibernate Advisor's
 * own {@code HIB-MAP-019} (which only sees JPA-declared {@code @Table(indexes=...)} metadata), this rule
 * sees the database's actual indexes — including ones created by a Flyway/Liquibase migration — so it
 * only fires when the physical schema genuinely has no supporting index, a high-confidence, low-noise
 * signal.
 */
final class HibernateMissingForeignKeyIndexRule extends AbstractDatabaseAdvisorRule {

    HibernateMissingForeignKeyIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-001",
                "Mapped foreign key column has no physical index",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.HIGH,
                "Cross-references mapped @ManyToOne/@OneToOne @JoinColumn foreign keys against the physical "
                        + "schema's actual indexes (not just JPA-declared @Table(indexes=...) metadata).",
                "Add a database index (via a migration) leading on the foreign key column. Hibernate loads the "
                        + "association's target through this column on every traversal, and cascading "
                        + "deletes/updates on the parent row scan the child table without it.",
                "https://vladmihalcea.com/how-to-map-a-onetomany-jpa-and-hibernate-association/"));
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
            if (entity.explicitTableName() == null || entity.foreignKeys().isEmpty()) {
                continue;
            }
            TableModel table = findTable(context, entity.explicitTableName());
            if (table == null) {
                continue;
            }
            for (MappedForeignKeyFacts foreignKey : entity.foreignKeys()) {
                String column = foreignKey.columns().get(0);
                if (table.hasColumn(column) && !table.hasLeadingIndexOn(column)) {
                    details.add(foreignKey.attributeDescription() + " maps foreign key column "
                            + entity.explicitTableName() + "." + column
                            + ", which has no leading index in the physical schema.");
                }
            }
        }
        return violation(details);
    }

    private TableModel findTable(DatabaseAdvisorContext context, String tableName) {
        for (SchemaSnapshot schema : context.availableSchemas()) {
            TableModel table = schema.table(tableName);
            if (table != null) {
                return table;
            }
        }
        return null;
    }
}
