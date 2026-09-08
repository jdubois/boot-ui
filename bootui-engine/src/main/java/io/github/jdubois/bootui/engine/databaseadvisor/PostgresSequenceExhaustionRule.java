package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/** Direction-aware sequence headroom, bounded by a known owning column as well as the sequence definition. */
final class PostgresSequenceExhaustionRule extends AbstractDatabaseAdvisorRule {

    static final int WARNING_PERCENT_USED = 80;

    PostgresSequenceExhaustionRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-PG-002",
                "PostgreSQL sequence nearing exhaustion",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects a cached sequence frontier at least " + WARNING_PERCENT_USED
                        + "% through reachable steps from its configured start toward its direction-aware bound, "
                        + "including a known owning-column limit. This snapshot is not a time-to-exhaustion estimate.",
                "Review actual generator use, cache reservation and dependent columns before a migration. "
                        + "If more range is required, coordinate widening the column and sequence bounds. "
                        + "Do not reset or restart based on archived rows: other stored or reserved identifiers "
                        + "can still collide. Cycling is excluded unless a narrower column fails before wrapping.",
                "https://www.postgresql.org/docs/current/view-pg-sequences.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.schemasOf(Dialect.POSTGRESQL);
        String skipReason = VendorRuleSupport.skipReason(
                context,
                definition().id(),
                schemas,
                VendorFindingKinds.POSTGRES_SEQUENCES,
                "No PostgreSQL datasource was detected.");
        if (skipReason != null) {
            return skipped(skipReason);
        }
        List<String> details = new ArrayList<>();
        int eligible = 0;
        for (SchemaSnapshot schema : schemas) {
            VendorRuleSupport.coverage(context, definition().id(), schema, VendorFindingKinds.POSTGRES_SEQUENCES);
            if (!VendorRuleSupport.available(schema, VendorFindingKinds.POSTGRES_SEQUENCES)) {
                continue;
            }
            for (PostgresSequenceUsage sequence :
                    schema.vendorFindings().findings(VendorFindingKinds.POSTGRES_SEQUENCES)) {
                if (sequence.percentUsed() < 0) {
                    unknown(
                            context,
                            schema.dataSourceName() + ": " + sequence.qualifiedName()
                                    + " has an unknown counter, start, increment or bound; headroom was not assessed.");
                    continue;
                }
                if (sequence.ownerColumn() != null && sequence.columnCapacity() == null) {
                    unknown(
                            context,
                            sequence.qualifiedName()
                                    + ": owning-column range is unknown; only sequence bounds were assessed.");
                } else {
                    eligible++;
                }
                checkSequence(schema, sequence, details);
            }
        }
        return VendorRuleSupport.assessed(this, context, eligible, details);
    }

    private void checkSequence(SchemaSnapshot schema, PostgresSequenceUsage sequence, List<String> details) {
        if ((sequence.cycle() && !sequence.limitedByColumn()) || sequence.effectiveBound() == null) {
            return;
        }
        int percentUsed = sequence.percentUsed();
        if (percentUsed < WARNING_PERCENT_USED) {
            return;
        }
        String limitedBy = sequence.limitedByColumn() ? " (limited by its owning column type)" : "";
        details.add(schema.dataSourceName() + ": sequence " + sequence.qualifiedName() + " at " + percentUsed
                + "% toward effective bound " + sequence.effectiveBound() + limitedBy + "; owner "
                + sequence.describeOwner() + "; reserved last_value " + sequence.lastValue()
                + " (start " + sequence.startValue() + ", increment " + sequence.incrementBy()
                + ", cache " + sequence.cacheSize() + "). Not committed rows.");
    }
}
