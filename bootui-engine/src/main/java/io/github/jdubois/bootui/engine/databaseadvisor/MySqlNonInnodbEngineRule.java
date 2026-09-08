package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Known nontransactional engines warrant an intent review, not a universal InnoDB migration. */
final class MySqlNonInnodbEngineRule extends AbstractDatabaseAdvisorRule {

    /** Engines with no transactions, no MVCC and table-level locking. */
    private static final Set<String> NON_TRANSACTIONAL_ENGINES =
            Set.of("myisam", "mrg_myisam", "merge", "memory", "heap", "csv", "archive", "blackhole", "aria");

    MySqlNonInnodbEngineRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-MYSQL-001",
                "Tables on a non-transactional storage engine",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Detects MySQL/MariaDB tables (information_schema.tables.ENGINE) on a non-transactional engine "
                        + "(MyISAM, MERGE, MEMORY, CSV, ARCHIVE, BLACKHOLE, Aria). Specialist transactional or "
                        + "purpose-built engines such as RocksDB, ColumnStore, NDB, FEDERATED, SPIDER and CONNECT "
                        + "are not reported.",
                "Check whether transactional rollback is required for this table or its engine is intentional. "
                        + "If a migration is needed, evaluate an appropriate transactional engine, dependencies "
                        + "and rewrite/locking costs first. Crash safety, locking and foreign-key support differ "
                        + "by engine; Aria may be crash-safe without supporting transactional rollback.",
                "https://dev.mysql.com/doc/refman/8.0/en/innodb-introduction.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.mySqlFamilySchemas();
        String skipReason = VendorRuleSupport.skipReason(
                context,
                definition().id(),
                schemas,
                VendorFindingKinds.MYSQL_TABLES,
                "No MySQL or MariaDB datasource was detected.");
        if (skipReason != null) {
            return skipped(skipReason);
        }
        List<String> details = new ArrayList<>();
        int eligible = 0;
        for (SchemaSnapshot schema : schemas) {
            VendorRuleSupport.coverage(context, definition().id(), schema, VendorFindingKinds.MYSQL_TABLES);
            if (!VendorRuleSupport.available(schema, VendorFindingKinds.MYSQL_TABLES)) {
                continue;
            }
            for (MySqlTableInfo table : schema.vendorFindings().findings(VendorFindingKinds.MYSQL_TABLES)) {
                if (table.engine() == null) {
                    unknown(context, table.qualifiedName() + ": storage engine is unknown.");
                    continue;
                }
                eligible++;
                if (isNonTransactional(table.engine())) {
                    details.add(schema.dataSourceName() + ": table " + table.qualifiedName() + " uses the "
                            + table.engine() + " engine, which does not provide transactional rollback."
                            + ("aria".equalsIgnoreCase(table.engine())
                                    ? " Aria can provide crash safety; that is distinct from transaction support."
                                    : ""));
                }
            }
        }
        return VendorRuleSupport.assessed(this, context, eligible, details);
    }

    private boolean isNonTransactional(String engine) {
        return engine != null && NON_TRANSACTIONAL_ENGINES.contains(engine.toLowerCase(Locale.ROOT));
    }
}
