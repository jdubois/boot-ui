package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedUniqueConstraintFacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cross-references a mapped unique constraint — a single-column {@code @Column(unique=true)} attribute or
 * a multi-column {@code @Table(uniqueConstraints=...)} constraint — against the physical schema's actual
 * unique indexes. Like {@code DB-HIB-001}'s missing foreign-key index check, this sees the database's
 * actual indexes (including ones created by a Flyway/Liquibase migration), so it only fires when the
 * physical schema genuinely enforces no such uniqueness: application code relying on the mapping's
 * assumed uniqueness guarantee can then insert duplicate rows under concurrent access, a data-integrity
 * risk on par with a missing foreign-key index.
 */
final class HibernateMissingUniqueIndexRule extends AbstractDatabaseAdvisorRule {

    HibernateMissingUniqueIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-005",
                "Mapped unique constraint has no backing physical unique index",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.HIGH,
                "Cross-references mapped @Column(unique=true) attributes and @Table(uniqueConstraints=...) "
                        + "constraints against the physical schema's actual unique indexes.",
                "Add a unique index or constraint (via a migration) covering the same column(s). Without a "
                        + "physical unique index, the database never enforces the mapping's uniqueness assumption, "
                        + "so concurrent inserts can create duplicate rows the application logic never expected.",
                "https://vladmihalcea.com/database-uniqueness-application-level-vs-database-level/"));
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
            if (entity.explicitTableName() == null || entity.uniqueConstraints().isEmpty()) {
                continue;
            }
            TableModel table = findTable(context, entity.explicitTableName());
            if (table == null) {
                continue;
            }
            for (MappedUniqueConstraintFacts uniqueConstraint : entity.uniqueConstraints()) {
                checkUniqueConstraint(entity, uniqueConstraint, table, details);
            }
        }
        return violation(details);
    }

    private void checkUniqueConstraint(
            MappedEntityFacts entity,
            MappedUniqueConstraintFacts uniqueConstraint,
            TableModel table,
            List<String> details) {
        if (uniqueConstraint.columns().isEmpty()
                || !uniqueConstraint.columns().stream().allMatch(table::hasColumn)) {
            return;
        }
        Set<String> mappedColumns = normalized(uniqueConstraint.columns());
        boolean backed = table.indexes().stream()
                .anyMatch(index -> index.unique() && normalized(index.columns()).equals(mappedColumns));
        if (!backed) {
            details.add(uniqueConstraint.description() + " declares a unique constraint on "
                    + entity.explicitTableName() + " " + uniqueConstraint.columns()
                    + ", which has no backing unique index in the physical schema.");
        }
    }

    private Set<String> normalized(List<String> columns) {
        return columns.stream().map(column -> column.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
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
