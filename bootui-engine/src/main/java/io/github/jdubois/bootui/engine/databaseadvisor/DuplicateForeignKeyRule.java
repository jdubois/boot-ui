package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class DuplicateForeignKeyRule extends AbstractDatabaseAdvisorRule {

    DuplicateForeignKeyRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-006",
                "Potential duplicate foreign-key definitions",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.LOW,
                "Compares exact qualified child-parent pairs plus known JDBC update/delete actions and deferrability.",
                "Review constraint definitions, match mode, enforcement, validation and dependencies. JDBC does not "
                        + "establish all these semantics; do not drop a constraint based only on structural overlap.",
                "https://www.postgresql.org/docs/current/ddl-constraints.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        int eligible = 0;
        for (SchemaSnapshot schema : context.availableSchemas()) {
            for (TableModel table : DatabaseAdvisorContext.analyzableTables(schema)) {
                List<ForeignKeyModel> keys = table.foreignKeys();
                for (int i = 0; i < keys.size(); i++) {
                    for (int j = i + 1; j < keys.size(); j++) {
                        ForeignKeyModel first = keys.get(i);
                        ForeignKeyModel second = keys.get(j);
                        if (!known(first) || !known(second)) {
                            unknown(
                                    context,
                                    table.qualifiedName() + ": foreign-key action or pairing semantics are unknown.");
                            continue;
                        }
                        eligible++;
                        if (Objects.equals(first.referencedCatalog(), second.referencedCatalog())
                                && Objects.equals(first.referencedSchema(), second.referencedSchema())
                                && Objects.equals(first.referencedTable(), second.referencedTable())
                                && Objects.equals(first.updateRule(), second.updateRule())
                                && Objects.equals(first.deleteRule(), second.deleteRule())
                                && Objects.equals(first.deferrability(), second.deferrability())
                                && compatibleKnown(first.enforced(), second.enforced())
                                && compatibleKnown(first.validated(), second.validated())
                                && compatibleKnown(first.matchType(), second.matchType())
                                && pairs(first).equals(pairs(second))) {
                            details.add(
                                    schema.dataSourceName() + ": " + table.qualifiedName() + " foreign keys "
                                            + first.name() + " and " + second.name()
                                            + " have potentially duplicate observed definitions; match/enforcement semantics require review.");
                        }
                    }
                }
                if (!table.metadata().foreignKeysRead()) {
                    unknown(context, table.qualifiedName() + ": foreign-key inventory is incomplete.");
                }
            }
        }
        return assessed(context, eligible, details);
    }

    private static boolean known(ForeignKeyModel fk) {
        return fk.consistent() && fk.updateRule() != null && fk.deleteRule() != null && fk.deferrability() != null;
    }

    private static boolean compatibleKnown(Object first, Object second) {
        return first == null || second == null || first.equals(second);
    }

    private record Pair(String child, String parent) {}

    private static Set<Pair> pairs(ForeignKeyModel fk) {
        Set<Pair> pairs = new HashSet<>();
        for (int i = 0; i < fk.columns().size(); i++) {
            pairs.add(new Pair(fk.columns().get(i), fk.referencedColumns().get(i)));
        }
        return pairs;
    }
}
