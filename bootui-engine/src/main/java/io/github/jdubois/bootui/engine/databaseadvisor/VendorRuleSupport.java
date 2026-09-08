package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared skip logic for the vendor rules, so "no datasource of this dialect", "this server version has no
 * such catalog view" and "the catalog refused to answer" all reach the user as an explicit {@code SKIPPED}
 * reason instead of an unearned clean result.
 */
final class VendorRuleSupport {

    private VendorRuleSupport() {}

    /**
     * The reason this rule cannot run, or {@code null} when at least one datasource can answer it.
     *
     * @param schemas the readable schemas of the rule's dialect family
     * @param kind the catalog augmentation the rule reads
     * @param noDatasourceReason the reason to report when no datasource of that dialect exists
     */
    static String skipReason(
            DatabaseAdvisorContext context,
            String ruleId,
            List<SchemaSnapshot> schemas,
            VendorFindingKind<?> kind,
            String noDatasourceReason) {
        if (schemas.isEmpty()) {
            return "Not applicable: " + noDatasourceReason;
        }
        // Journal every applicable datasource before an early return, including supported/unsupported mixes.
        schemas.forEach(schema -> coverage(context, ruleId, schema, kind));
        List<String> reasons = new ArrayList<>();
        for (SchemaSnapshot schema : schemas) {
            VendorAugmentation<?> augmentation = schema.vendorFindings().augmentation(kind);
            if (augmentation.available()) {
                return null;
            }
            reasons.add(schema.dataSourceName() + ": " + augmentation.reason());
        }
        return String.join("; ", reasons);
    }

    static boolean available(SchemaSnapshot schema, VendorFindingKind<?> kind) {
        return schema.vendorFindings().available(kind);
    }

    static boolean complete(SchemaSnapshot schema, VendorFindingKind<?> kind) {
        return available(schema, kind)
                && !schema.vendorFindings().augmentation(kind).truncated();
    }

    static void coverage(
            DatabaseAdvisorContext context, String ruleId, SchemaSnapshot schema, VendorFindingKind<?>... kinds) {
        for (VendorFindingKind<?> kind : kinds) {
            VendorAugmentation<?> augmentation = schema.vendorFindings().augmentation(kind);
            if (!augmentation.available() || augmentation.truncated()) {
                context.unknown(
                        ruleId,
                        schema.dataSourceName() + ": "
                                + (augmentation.truncated()
                                        ? "Catalog results were truncated."
                                        : augmentation.reason()));
            }
        }
    }

    static DatabaseAdvisorRuleResultDto assessed(
            AbstractDatabaseAdvisorRule rule, DatabaseAdvisorContext context, int eligible, List<String> details) {
        if (details.isEmpty()) {
            List<String> gaps = context.evaluationDiagnostics().stream()
                    .filter(diagnostic -> rule.definition().id().equals(diagnostic.source()))
                    .map(SchemaDiagnostic::message)
                    .limit(3)
                    .toList();
            if (!gaps.isEmpty()) {
                return rule.skipped("Coverage is incomplete: " + String.join("; ", gaps));
            }
        }
        return rule.assessed(context, eligible, details);
    }

    /** Snapshot progress through reachable steps, not rows, elapsed time, or committed NEXTVAL calls. */
    static int percentUsed(BigInteger frontier, BigInteger origin, BigInteger bound, BigInteger increment) {
        if (frontier == null || origin == null || bound == null || increment == null || increment.signum() == 0) {
            return -1;
        }
        BigInteger direction = BigInteger.valueOf(increment.signum());
        BigInteger range = bound.subtract(origin).multiply(direction);
        BigInteger progress = frontier.subtract(origin).multiply(direction);
        if (progress.signum() < 0) {
            return -1;
        }
        if (range.signum() < 0) {
            return 100;
        }
        BigInteger steps = range.divide(increment.abs());
        if (steps.signum() == 0) {
            return 100;
        }
        return progress.divide(increment.abs())
                .multiply(BigInteger.valueOf(100))
                .divide(steps)
                .min(BigInteger.valueOf(100))
                .intValue();
    }
}
