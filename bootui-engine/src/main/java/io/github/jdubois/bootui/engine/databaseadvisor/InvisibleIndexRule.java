package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An index the optimizer ignores by default while every write still maintains it: MySQL 8.0+ invisible
 * ({@code information_schema.statistics.IS_VISIBLE = 'NO'}), MariaDB 10.6+ ignored ({@code IGNORED = 'YES'}) and
 * Oracle invisible ({@code ALL_INDEXES.VISIBILITY = 'INVISIBLE'}) indexes. Such a state is usually a staged
 * "soft drop" or a trial; this rule asks whether that change is finished.
 *
 * <p>Unknown visibility is never a finding. Constraint-backing, unusable (see {@code DB-ORACLE-001}) and Oracle
 * automatic-indexing candidates ({@code SYS_AI_} names, which Oracle deliberately keeps invisible in report-only
 * mode) are excluded. A UNIQUE invisible index still enforces uniqueness, so dropping it is never implied.</p>
 */
final class InvisibleIndexRule extends AbstractDatabaseAdvisorRule {

    private static final String ORACLE_AUTOMATIC_INDEX_PREFIX = "SYS_AI_";

    InvisibleIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-010",
                "Invisible or ignored indexes",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.LOW,
                "Detects MySQL invisible, MariaDB ignored and Oracle invisible indexes, which the optimizer does not "
                        + "use by default but every INSERT, UPDATE and DELETE still maintains. Constraint-backing, "
                        + "unusable and Oracle automatic-indexing indexes are excluded; unknown visibility is not a finding.",
                "Decide whether the staged change is finished: make the index visible again, or drop it after reviewing "
                        + "constraints, hints and dependencies. An invisible UNIQUE index still enforces uniqueness. "
                        + "MySQL and Oracle sessions can opt in to invisible indexes; MariaDB ignored indexes cannot be "
                        + "re-enabled by hints.",
                "https://dev.mysql.com/doc/refman/8.4/en/invisible-indexes.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        int eligible = 0;
        for (SchemaSnapshot schema : context.availableSchemas()) {
            if (!schema.dialect().isMySqlFamily() && schema.dialect() != Dialect.ORACLE) {
                continue;
            }
            DialectCapabilities capabilities = DialectCapabilities.of(schema.dialect(), schema.version());
            if (schema.dialect().isMySqlFamily() && !capabilities.indexVisibility()) {
                // Invisible (MySQL 8.0) and ignored (MariaDB 10.6) indexes do not exist on older servers.
                continue;
            }
            if (schema.dialect() == Dialect.ORACLE && !capabilities.oracleCatalog()) {
                unknown(context, schema.dataSourceName() + ": index visibility is read only on Oracle 19c or later.");
                continue;
            }
            for (TableModel table : DatabaseAdvisorContext.analyzableTables(schema)) {
                if (!table.metadata().indexesRead()) {
                    unknown(
                            context,
                            schema.dataSourceName() + ": " + table.qualifiedName() + " index inventory is incomplete.");
                }
                boolean unknownVisibility = false;
                for (IndexModel index : table.indexes()) {
                    if (excluded(index)) {
                        continue;
                    }
                    switch (index.visibility()) {
                        case VISIBLE -> eligible++;
                        case INVISIBLE -> {
                            eligible++;
                            details.add(schema.dataSourceName() + ": " + table.qualifiedName() + " "
                                    + (index.unique() ? "unique index " : "index ") + index.name() + " "
                                    + index.columnNames() + " is " + invisibleState(schema.dialect())
                                    + ": still maintained on every write but not used by the optimizer by default.");
                        }
                        case UNKNOWN -> unknownVisibility = true;
                    }
                }
                if (unknownVisibility) {
                    unknown(
                            context,
                            schema.dataSourceName() + ": " + table.qualifiedName()
                                    + " has index visibility the catalog did not report.");
                }
            }
        }
        return assessed(context, eligible, details);
    }

    private static boolean excluded(IndexModel index) {
        String name = index.name() == null ? "" : index.name().toUpperCase(Locale.ROOT);
        return index.backingConstraint() != null
                || index.automatic()
                || index.validity() == IndexModel.Validity.INVALID
                || name.startsWith(ORACLE_AUTOMATIC_INDEX_PREFIX);
    }

    private static String invisibleState(Dialect dialect) {
        return dialect == Dialect.MARIADB ? "IGNORED" : "INVISIBLE";
    }
}
