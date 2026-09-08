package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reserved sequence frontier with direction-aware bounds and explicitly linked NUMBER(p,0) identities. */
final class OracleSequenceExhaustionRule extends AbstractDatabaseAdvisorRule {

    static final int WARNING_PERCENT_USED = 80;

    OracleSequenceExhaustionRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-ORACLE-003",
                "Oracle sequence or identity generator nearing exhaustion",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects a reserved LAST_NUMBER frontier at least " + WARNING_PERCENT_USED
                        + "% through the direction-aware configured range, bounded by a known NUMBER(p,0) identity. "
                        + "Session, scalable and sharded sequences are excluded. This is not consumed rows or time remaining.",
                "Review cache reservation and actual use before widening generator bounds or dependent columns. "
                        + "For an identity, use supported ALTER TABLE identity/column changes, not direct ALTER SEQUENCE "
                        + "against its system-generated sequence. For an ordinary sequence, review ALTER SEQUENCE limits "
                        + "and consumers. Do not restart/reset based on archiving: stored or reserved identifiers may collide.",
                "https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/ALL_SEQUENCES.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.schemasOf(Dialect.ORACLE);
        String reason = VendorRuleSupport.skipReason(
                context,
                definition().id(),
                schemas,
                VendorFindingKinds.ORACLE_SEQUENCES,
                "No Oracle datasource was detected.");
        if (reason != null) {
            return skipped(reason);
        }
        List<String> details = new ArrayList<>();
        int eligible = 0;
        for (SchemaSnapshot schema : schemas) {
            VendorRuleSupport.coverage(
                    context,
                    definition().id(),
                    schema,
                    VendorFindingKinds.ORACLE_SEQUENCES,
                    VendorFindingKinds.ORACLE_IDENTITY_COLUMNS);
            for (OracleSequenceUsage sequence : schema.vendorFindings().findings(VendorFindingKinds.ORACLE_SEQUENCES)) {
                if (sequence.excluded()) {
                    continue;
                }
                List<OracleIdentityColumn> owners =
                        schema.vendorFindings().findings(VendorFindingKinds.ORACLE_IDENTITY_COLUMNS).stream()
                                .filter(column -> Objects.equals(column.schema(), sequence.schema())
                                        && Objects.equals(column.sequenceName(), sequence.sequence()))
                                .toList();
                OracleIdentityColumn identity = owners.size() == 1 ? owners.get(0) : null;
                boolean identityKnown = owners.size() <= 1
                        && VendorRuleSupport.complete(schema, VendorFindingKinds.ORACLE_IDENTITY_COLUMNS);
                if (!identityKnown || (identity != null && identity.capacity() == null)) {
                    unknown(
                            context,
                            sequence.qualifiedName()
                                    + ": identity linkage or numeric precision/scale is unknown; only sequence bounds were assessed.");
                }
                int percent = sequence.percentUsed(identity);
                if (percent < 0) {
                    unknown(context, sequence.qualifiedName() + ": counter, increment or range is unknown.");
                    continue;
                }
                if (identityKnown && (identity == null || identity.capacity() != null)) {
                    eligible++;
                }
                if ((sequence.cycle() && !sequence.limitedByColumn(identity)) || percent < WARNING_PERCENT_USED) {
                    continue;
                }
                details.add(
                        schema.dataSourceName() + ": sequence " + sequence.qualifiedName()
                                + (identity == null ? "" : " backing IDENTITY column " + identity.qualifiedColumn())
                                + " is at " + percent + "% toward effective bound " + sequence.effectiveBound(identity)
                                + " (reserved last_number " + sequence.lastNumber() + ", increment "
                                + sequence.incrementBy()
                                + ", cache " + sequence.cacheSize()
                                + "). Cache reservation can report an early warning "
                                + "without proving identifiers have been used; the configured range does not reveal original START WITH.");
            }
        }
        return VendorRuleSupport.assessed(this, context, eligible, details);
    }
}
