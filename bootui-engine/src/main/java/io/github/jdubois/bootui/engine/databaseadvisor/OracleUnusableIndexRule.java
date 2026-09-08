package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * Oracle-specific: an index the catalog reports unusable — {@code all_indexes.status = 'UNUSABLE'} for an
 * ordinary index, or an individual partition/subpartition reported {@code UNUSABLE} for a partitioned one,
 * where the index's own {@code status} reads {@code N/A} instead. DML consequences depend on the kind of
 * index and enforcement dependencies; {@code SKIP_UNUSABLE_INDEXES} does not generally bypass unusable
 * unique indexes.
 *
 * <p>Domain indexes ({@code index_type = 'DOMAIN'}, e.g. Oracle Text or Spatial) are excluded: their status
 * semantics are governed by the domain index implementation's own auxiliary objects, which {@code
 * all_indexes.status} alone cannot reliably describe, and a wrong "this is broken" here would be worse than
 * staying quiet. Every other index type — normal, function-based, bitmap, LOB, IOT-backing — uses the
 * standard {@code status} semantics and is reported the same way, including one Oracle created automatically
 * to back a primary key or unique constraint: an unusable constraint-backing index is, if anything, more
 * consequential to miss.</p>
 */
final class OracleUnusableIndexRule extends AbstractDatabaseAdvisorRule {

    OracleUnusableIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-ORACLE-001",
                "Unusable Oracle indexes",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects Oracle indexes reported UNUSABLE by all_indexes.status, and — for a partitioned "
                        + "index, whose own status reads N/A — individual UNUSABLE partitions/subpartitions in "
                        + "all_ind_partitions/all_ind_subpartitions. Domain indexes are excluded: their status "
                        + "semantics need their own domain index implementation to interpret reliably.",
                "Review the exact index/partition type and constraint dependencies before a supported rebuild "
                        + "during a maintenance window. DML behavior depends on index kind, uniqueness enforcement "
                        + "and SKIP_UNUSABLE_INDEXES; that setting does not generally bypass unusable unique indexes. "
                        + "LOB/IOT and partitioned objects need their supported object-specific maintenance path.",
                "https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/ALTER-INDEX.html"));
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
        if (skipReason != null
                && schemas.stream()
                        .noneMatch(schema -> VendorRuleSupport.available(
                                schema, VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS))) {
            return skipped(skipReason);
        }
        List<String> details = new ArrayList<>();
        int eligible = 0;
        for (SchemaSnapshot schema : schemas) {
            VendorRuleSupport.coverage(context, definition().id(), schema, VendorFindingKinds.ORACLE_INDEX_DETAILS);
            boolean hasPartitioned = schema.vendorFindings().findings(VendorFindingKinds.ORACLE_INDEX_DETAILS).stream()
                    .anyMatch(OracleIndexDetail::partitioned);
            if (hasPartitioned) {
                VendorRuleSupport.coverage(
                        context, definition().id(), schema, VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS);
            }
            for (OracleIndexDetail index : schema.vendorFindings().findings(VendorFindingKinds.ORACLE_INDEX_DETAILS)) {
                if (index.domain()) {
                    continue;
                }
                if (index.partitioned()) {
                    if (VendorRuleSupport.complete(schema, VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS)) {
                        eligible++;
                    }
                } else if (index.usable() || index.unusable()) {
                    eligible++;
                } else {
                    unknown(context, index.qualifiedTable() + ": index state is unknown, not explicit UNUSABLE.");
                }
            }
            if (VendorRuleSupport.available(schema, VendorFindingKinds.ORACLE_INDEX_DETAILS)) {
                checkOrdinaryIndexes(schema, details);
            }
            if (VendorRuleSupport.available(schema, VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS)) {
                checkPartitions(schema, details);
            }
        }
        return VendorRuleSupport.assessed(this, context, eligible, details);
    }

    private void checkOrdinaryIndexes(SchemaSnapshot schema, List<String> details) {
        for (OracleIndexDetail index : schema.vendorFindings().findings(VendorFindingKinds.ORACLE_INDEX_DETAILS)) {
            if (index.partitioned() || index.domain() || !index.unusable()) {
                continue;
            }
            String flavor = index.automatic() ? " (system-generated name; not proof of constraint ownership)" : "";
            details.add(schema.dataSourceName() + ": index " + index.schema() + "." + index.index() + flavor
                    + " on table " + index.qualifiedTable() + " is UNUSABLE (" + index.indexType() + ").");
        }
    }

    private void checkPartitions(SchemaSnapshot schema, List<String> details) {
        for (OracleIndexPartitionStatus partition :
                schema.vendorFindings().findings(VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS)) {
            if (!partition.unusable()) {
                continue;
            }
            boolean domain = schema.vendorFindings().findings(VendorFindingKinds.ORACLE_INDEX_DETAILS).stream()
                    .anyMatch(index -> java.util.Objects.equals(index.schema(), partition.schema())
                            && java.util.Objects.equals(index.index(), partition.index())
                            && index.domain());
            if (domain) {
                continue;
            }
            String level = partition.subpartition() ? "subpartition" : "partition";
            details.add(schema.dataSourceName() + ": " + level + " " + partition.partitionName() + " of index "
                    + partition.schema() + "." + partition.index() + " on table " + partition.qualifiedTable()
                    + " is UNUSABLE.");
        }
    }
}
