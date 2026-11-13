package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.List;

/**
 * An Oracle global index whose {@code all_indexes.orphaned_entries} column reports {@code YES}: deferred
 * global-index maintenance after a partition {@code DROP} or {@code TRUNCATE ... UPDATE INDEXES} has left
 * orphaned index entries awaiting cleanup ({@code DBMS_PART.CLEANUP_GIDX} / {@code PMO_DEFERRED_GIDX_MAINT_JOB}).
 */
final class OracleOrphanedIndexEntriesRule extends AbstractDatabaseAdvisorRule {

    OracleOrphanedIndexEntriesRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-ORACLE-004",
                "Oracle global indexes with orphaned partition entries",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.LOW,
                "Detects Oracle global indexes with all_indexes.orphaned_entries = 'YES' left by deferred "
                        + "partition maintenance (DROP/TRUNCATE PARTITION ... UPDATE INDEXES). Domain and "
                        + "already-UNUSABLE indexes are excluded.",
                "Verify whether PMO_DEFERRED_GIDX_MAINT_JOB is scheduled and completing, or run "
                        + "DBMS_PART.CLEANUP_GIDX / ALTER INDEX ... COALESCE CLEANUP during an appropriate window "
                        + "so index scans do not continue traversing entries from dropped partitions.",
                "https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/ALL_INDEXES.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.schemasOf(Dialect.ORACLE);
        String skipReason = VendorRuleSupport.skipReason(
                context,
                definition().id(),
                schemas,
                VendorFindingKinds.ORACLE_INDEX_DETAILS,
                "No Oracle datasource was detected.");
        if (skipReason != null) {
            return skipped(skipReason);
        }
        DatabaseFindings details = new DatabaseFindings();
        int eligible = 0;
        for (SchemaSnapshot schema : schemas) {
            VendorRuleSupport.coverage(context, definition().id(), schema, VendorFindingKinds.ORACLE_INDEX_DETAILS);
            if (!VendorRuleSupport.available(schema, VendorFindingKinds.ORACLE_INDEX_DETAILS)) {
                continue;
            }
            for (OracleIndexDetail index : schema.vendorFindings().findings(VendorFindingKinds.ORACLE_INDEX_DETAILS)) {
                if (index.domain() || index.unusable()) {
                    continue;
                }
                if ("YES".equalsIgnoreCase(index.orphanedEntries())) {
                    eligible++;
                    details.add(
                            schema.dataSourceName() + ": index " + index.schema() + "." + index.index() + " on table "
                                    + index.qualifiedTable()
                                    + " has ORPHANED_ENTRIES = YES in all_indexes from deferred partition maintenance ("
                                    + index.indexType() + ").",
                            DatabaseIdentities.table(
                                    schema.dataSourceName(),
                                    null,
                                    index.schema(),
                                    index.table(),
                                    "index=" + index.index()));
                } else if ("NO".equalsIgnoreCase(index.orphanedEntries())) {
                    eligible++;
                } else {
                    unknown(
                            context,
                            index.qualifiedTable() + ": index orphaned_entries state is unknown in all_indexes.");
                }
            }
        }
        return VendorRuleSupport.assessed(this, context, eligible, details);
    }
}
