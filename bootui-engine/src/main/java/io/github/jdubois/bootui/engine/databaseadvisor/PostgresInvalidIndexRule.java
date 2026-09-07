package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/** Explicit catalog flags warrant review; invalid UNIQUE indexes can still reject conflicting writes. */
final class PostgresInvalidIndexRule extends AbstractDatabaseAdvisorRule {

    PostgresInvalidIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-PG-001",
                "Invalid PostgreSQL indexes",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects PostgreSQL indexes reported unusable by pg_index (indisvalid/indisready/indislive), "
                        + "excluding partitioned index parents, extension-owned indexes, and (PostgreSQL 12+) "
                        + "indexes currently being built CONCURRENTLY, which are transiently invalid by design.",
                "Confirm concurrent build/drop activity and inspect constraint dependencies before choosing "
                        + "a supported repair. REINDEX CONCURRENTLY is version- and index-kind-dependent. "
                        + "Do not blindly drop a unique or constraint-backing index: an invalid UNIQUE index "
                        + "may still reject writes after a failed concurrent build. Maintenance depends on "
                        + "indisready/indislive; catalog flags alone do not establish its ongoing write cost.",
                "https://www.postgresql.org/docs/current/sql-createindex.html#SQL-CREATEINDEX-CONCURRENTLY"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.schemasOf(Dialect.POSTGRESQL);
        String skipReason = VendorRuleSupport.skipReason(
                schemas, VendorFindingKinds.POSTGRES_INVALID_INDEXES, "No PostgreSQL datasource was detected.");
        if (skipReason != null) {
            return skipped(skipReason);
        }
        List<String> details = new ArrayList<>();
        int eligible = 0;
        for (SchemaSnapshot schema : schemas) {
            VendorRuleSupport.coverage(context, definition().id(), schema, VendorFindingKinds.POSTGRES_INVALID_INDEXES);
            if (!VendorRuleSupport.available(schema, VendorFindingKinds.POSTGRES_INVALID_INDEXES)) {
                continue;
            }
            if (VendorRuleSupport.complete(schema, VendorFindingKinds.POSTGRES_INVALID_INDEXES)
                    && schema.vendorFindings()
                            .findings(VendorFindingKinds.POSTGRES_INVALID_INDEXES)
                            .isEmpty()) {
                eligible++;
            }
            for (PostgresInvalidIndex index :
                    schema.vendorFindings().findings(VendorFindingKinds.POSTGRES_INVALID_INDEXES)) {
                if (!index.explicitlyInvalid()) {
                    if (index.valid() == null || index.ready() == null || index.live() == null) {
                        unknown(context, index.qualifiedTable() + ": index flags are unknown.");
                    } else {
                        eligible++;
                    }
                    continue;
                }
                eligible++;
                String uniquenessImpact = Boolean.TRUE.equals(index.unique())
                        ? " This index is UNIQUE and may still reject conflicting writes after a failed concurrent build; "
                                + "invalid does not establish loss of enforcement."
                        : "";
                details.add(schema.dataSourceName() + ": index " + index.index() + " on table " + index.qualifiedTable()
                        + " has explicit invalid/unready/dead state (" + index.describeFlags()
                        + "). Concurrent build/drop activity can be transient." + uniquenessImpact);
            }
        }
        return VendorRuleSupport.assessed(this, context, eligible, details);
    }
}
