package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A structural access-path review, not a claim that every foreign key needs another index. */
final class MissingForeignKeyIndexRule extends AbstractDatabaseAdvisorRule {

    MissingForeignKeyIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-002",
                "Foreign-key access-path review",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Identifies foreign keys with no observed ordinary index leading on the full child column set. "
                        + "Equality lookups permit any leading order when the access method supports it.",
                "Review parent-key updates/deletes and representative query plans before adding an index. "
                        + "Partial, prefix and special indexes need individual assessment; no table scan is proven.",
                "https://www.postgresql.org/docs/current/ddl-constraints.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        int eligible = 0;
        for (SchemaSnapshot schema : context.availableSchemas()) {
            for (TableModel table : DatabaseAdvisorContext.analyzableTables(schema)) {
                if (!table.metadata().foreignKeysRead() || !table.metadata().indexesRead()) {
                    unknown(
                            context,
                            table.qualifiedName() + ": complete foreign-key and index inventories are required.");
                }
                for (ForeignKeyModel fk : table.foreignKeys()) {
                    if (!fk.consistent()) {
                        unknown(context, table.qualifiedName() + ": foreign-key pairing is incomplete.");
                        continue;
                    }
                    boolean supported = table.indexes().stream()
                            .anyMatch(index -> ordinaryMethod(index.method())
                                    && index.supportsLeadingEqualityAnyOrder(fk.columns()));
                    if (supported) {
                        eligible++;
                        continue;
                    }
                    if (!table.metadata().indexesRead()) {
                        continue;
                    }
                    boolean uncertain = table.indexes().stream()
                            .anyMatch(index -> !ordinaryMethod(index.method())
                                    || index.partial()
                                    || index.hasExpressionKeyPart()
                                    || index.hasPrefixKeyPart()
                                    || index.specialized()
                                    || index.partitioned()
                                    || index.validity() == IndexModel.Validity.UNKNOWN
                                    || index.visibility() == IndexModel.Visibility.UNKNOWN);
                    if (uncertain || schema.dialect().isMySqlFamily()) {
                        unknown(
                                context,
                                schema.dataSourceName() + ": " + table.qualifiedName() + " foreign key "
                                        + fk.name()
                                        + (schema.dialect().isMySqlFamily()
                                                ? " has no observed supporting index although this engine normally creates required FK indexes; reconcile metadata first."
                                                : " may have an access path whose applicability cannot be established from metadata."));
                        continue;
                    }
                    eligible++;
                    details.add(schema.dataSourceName() + ": " + table.qualifiedName() + " foreign key " + fk.name()
                            + " " + fk.columns()
                            + " has no observed usable ordinary index leading on the full column set. "
                            + "Review parent modifications and query plans; this is not proof of a table scan.");
                }
            }
        }
        return assessed(context, eligible, details);
    }

    private static boolean ordinaryMethod(String method) {
        return method != null
                && List.of("btree", "b-tree", "normal", "clustered").contains(method.toLowerCase(Locale.ROOT));
    }
}
