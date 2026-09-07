package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/** Expanded publication membership determines which relation's replica identity is required. */
final class PostgresReplicaIdentityRule extends AbstractDatabaseAdvisorRule {

    PostgresReplicaIdentityRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-PG-004",
                "PostgreSQL table lacking usable replica identity",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Detects missing replica identity for expanded explicit/all-table/schema publication membership "
                        + "that publishes UPDATE or DELETE, respecting partition-root publication behavior.",
                "Review the publication's UPDATE/DELETE requirements and choose a suitable primary key, eligible "
                        + "replica identity index, or FULL identity after considering its replication cost. "
                        + "Published UPDATE/DELETE operations can fail without identity even with no subscriber attached.",
                "https://www.postgresql.org/docs/current/logical-replication-publication.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.schemasOf(Dialect.POSTGRESQL);
        String reason = VendorRuleSupport.skipReason(
                schemas,
                VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES,
                "No PostgreSQL datasource was detected.");
        if (reason != null) {
            return skipped(reason);
        }
        List<String> details = new ArrayList<>();
        int eligible = 0;
        for (SchemaSnapshot schema : schemas) {
            VendorRuleSupport.coverage(
                    context, definition().id(), schema, VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES);
            for (PostgresReplicaIdentityCandidate candidate :
                    schema.vendorFindings().findings(VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES)) {
                if (candidate.replicaIdentity() == null
                        || !List.of("d", "n", "f", "i").contains(candidate.replicaIdentity())) {
                    unknown(context, candidate.qualifiedTable() + ": replica identity is unknown.");
                    continue;
                }
                boolean missing = candidate.nothing();
                if (candidate.usesDefault()) {
                    TableModel table = schema.table(null, candidate.schema(), candidate.table());
                    if (table == null || !table.metadata().primaryKeyRead()) {
                        unknown(
                                context,
                                candidate.qualifiedTable() + ": DEFAULT identity needs complete primary-key metadata.");
                        continue;
                    }
                    missing = table.primaryKeyColumns().isEmpty();
                } else if ("i".equals(candidate.replicaIdentity())) {
                    if (candidate.hasIdentityIndex() == null) {
                        unknown(context, candidate.qualifiedTable() + ": selected identity index state is unknown.");
                        continue;
                    }
                    missing = !candidate.hasIdentityIndex();
                }
                eligible++;
                if (missing) {
                    details.add(schema.dataSourceName() + ": " + candidate.qualifiedTable()
                            + " publishes UPDATE/DELETE but has no usable replica identity ("
                            + (candidate.nothing()
                                    ? "NOTHING"
                                    : candidate.usesDefault()
                                            ? "DEFAULT with no primary key"
                                            : "selected index not usable")
                            + "). Subscriber attachment is not required for affected writes to fail.");
                }
            }
        }
        return VendorRuleSupport.assessed(this, context, eligible, details);
    }
}
