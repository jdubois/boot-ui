package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Signedness-aware next-value snapshot, not committed row counts or a transactional capacity guarantee. */
final class MySqlAutoIncrementExhaustionRule extends AbstractDatabaseAdvisorRule {

    static final int WARNING_PERCENT_USED = 80;

    MySqlAutoIncrementExhaustionRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-MYSQL-003",
                "MySQL/MariaDB AUTO_INCREMENT nearing exhaustion",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects a reported information_schema.tables.AUTO_INCREMENT next/reserved counter at least "
                        + WARNING_PERCENT_USED + "% of the signed/unsigned capacity of its AUTO_INCREMENT column's "
                        + "integer type, without equating reservation with committed identifiers.",
                "Review the observed counter and generator configuration, then plan a compatible widening migration "
                        + "for the generated column and referencing columns if required. Catalog values may be stale "
                        + "or reserved rather than committed IDs; this threshold does not predict time remaining. "
                        + "Do not reset the counter based on this snapshot.",
                "https://dev.mysql.com/doc/refman/8.0/en/example-auto-increment.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.mySqlFamilySchemas();
        String skipReason = VendorRuleSupport.skipReason(
                schemas,
                VendorFindingKinds.MYSQL_AUTO_INCREMENT_COLUMNS,
                "No MySQL or MariaDB datasource was detected.");
        if (skipReason != null) {
            return skipped(skipReason);
        }
        List<String> details = new ArrayList<>();
        int eligible = 0;
        for (SchemaSnapshot schema : schemas) {
            VendorRuleSupport.coverage(
                    context,
                    definition().id(),
                    schema,
                    VendorFindingKinds.MYSQL_AUTO_INCREMENT_COLUMNS,
                    VendorFindingKinds.MYSQL_TABLES);
            if (!VendorRuleSupport.available(schema, VendorFindingKinds.MYSQL_AUTO_INCREMENT_COLUMNS)
                    || !VendorRuleSupport.available(schema, VendorFindingKinds.MYSQL_TABLES)) {
                continue;
            }
            for (MySqlAutoIncrementColumn column :
                    schema.vendorFindings().findings(VendorFindingKinds.MYSQL_AUTO_INCREMENT_COLUMNS)) {
                BigInteger counter = MySqlCatalogReader.nextAutoIncrement(schema, column.schema(), column.table());
                if (column.capacity() == null || counter == null || counter.signum() < 0) {
                    unknown(
                            context,
                            column.qualifiedTable() + ": AUTO_INCREMENT counter or column capacity is unknown.");
                    continue;
                }
                eligible++;
                checkColumn(schema, column, details);
            }
        }
        return VendorRuleSupport.assessed(this, context, eligible, details);
    }

    private void checkColumn(SchemaSnapshot schema, MySqlAutoIncrementColumn column, List<String> details) {
        BigInteger capacity = column.capacity();
        BigInteger nextValue = MySqlCatalogReader.nextAutoIncrement(schema, column.schema(), column.table());
        if (capacity == null || capacity.signum() <= 0 || nextValue == null || nextValue.signum() < 0) {
            // An unclassified type or an AUTO_INCREMENT the server did not report is not a clean result and
            // not a finding either: there is nothing to measure.
            return;
        }
        int percentUsed = new BigDecimal(nextValue)
                .multiply(BigDecimal.valueOf(100))
                .divide(new BigDecimal(capacity), 0, RoundingMode.DOWN)
                .min(BigDecimal.valueOf(100))
                .intValue();
        if (percentUsed < WARNING_PERCENT_USED) {
            return;
        }
        details.add(schema.dataSourceName() + ": " + column.qualifiedTable() + "." + column.column() + " ("
                + column.columnType() + ") is at " + percentUsed + "% of its AUTO_INCREMENT capacity (next value "
                + nextValue + " of " + capacity
                + "). This is a possibly cached/stale/reserved counter, not committed rows. "
                + persistenceNote(schema.dialect(), schema.version()));
    }

    static String persistenceNote(Dialect dialect, DatabaseVersion version) {
        if (dialect == Dialect.MARIADB) {
            if (!version.known() || (version.major() == 10 && version.minor() == 2 && version.patch() < 0)) {
                return "MariaDB InnoDB counter persistence depends on version (persistent from 10.2.4).";
            }
            return version.atLeast(10, 2, 4)
                    ? "MariaDB InnoDB counters are persistent from 10.2.4, but allocation is not transactional."
                    : "Before MariaDB 10.2.4, InnoDB counters can be reconstructed after restart.";
        }
        return "MySQL InnoDB counters are persistent from 8.0; persistence is not a committed-row count.";
    }
}
