package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.engine.sqltrace.SqlStatementNormalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded observations of different retained SQL texts with the same normalized shape and predicate literals. */
final class SqlLiteralConcatenationRule extends AbstractDatabaseAdvisorRule {

    /** Distinct raw texts of one shape needed before the shape is reported at all. */
    private static final int MIN_VARIANTS = 2;

    /** Distinct raw texts tracked per shape, bounding memory under a high-cardinality workload. */
    private static final int MAX_TRACKED_VARIANTS = 64;

    /** Shapes examined, bounding the scan when an application runs thousands of distinct statements. */
    private static final int MAX_TRACKED_SHAPES = 500;

    SqlLiteralConcatenationRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-RUNTIME-001",
                "SQL text variations with predicate literals",
                DatabaseAdvisorCategory.RUNTIME_SQL,
                DatabaseAdvisorRuleSupport.LOW,
                "Observes distinct retained SQL texts sharing a normalized shape and containing predicate literals. "
                        + "Text differences may be in projections, comments or whitespace; this does not identify "
                        + "which part changed or why. Examines at most 500 eligible shapes in the retained SQL Trace "
                        + "window and counts at most 64 distinct texts per shape. No SQL text is displayed.",
                "Review the existing SQL Trace evidence and its capture window to understand these text variations. "
                        + "Framework-generated constants and formatting differences can explain this observation; "
                        + "it does not establish a defect or prescribe a query change.",
                "https://docs.oracle.com/en/java/javase/17/docs/api/java.sql/java/sql/PreparedStatement.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SqlTraceEntryDto> statements = context.observedStatements();
        if (statements.isEmpty()) {
            return skipped("No statements have been captured by SQL Trace, so runtime SQL could not be "
                    + "inspected. Enable SQL Trace and exercise the application, then run the checks again.");
        }

        Map<String, ShapeEvidence> byShape = new LinkedHashMap<>();
        int readableStatements = 0;
        for (SqlTraceEntryDto statement : statements) {
            if (statement.sql() == null || statement.sql().isBlank()) {
                continue;
            }
            readableStatements++;
            SqlStatementNormalizer.Result normalized = SqlStatementNormalizer.normalize(statement.sql());
            if (normalized.predicateLiteralCount() == 0) {
                continue;
            }
            if (!byShape.containsKey(normalized.fingerprint()) && byShape.size() >= MAX_TRACKED_SHAPES) {
                continue;
            }
            byShape.computeIfAbsent(
                            normalized.fingerprint(),
                            key -> new ShapeEvidence(digest(key).substring(0, 16)))
                    .add(statement);
        }
        if (readableStatements == 0) {
            return skipped("No readable SQL text was retained in the SQL Trace window.");
        }

        List<ShapeEvidence> reportable = byShape.values().stream()
                .filter(ShapeEvidence::isReportable)
                .sorted(Comparator.comparingInt(ShapeEvidence::variantCount)
                        .reversed()
                        .thenComparing(ShapeEvidence::shape))
                .toList();
        if (reportable.isEmpty()) {
            return pass();
        }

        List<String> details = new ArrayList<>();
        for (ShapeEvidence evidence : reportable) {
            details.add(evidence.describe(statements.size()));
        }
        return violation(details);
    }

    private static String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", ex);
        }
    }

    /** One normalized statement shape and the counts that decide whether it is reportable. */
    private static final class ShapeEvidence {

        private final String shape;
        private final Set<String> rawTextHashes = new LinkedHashSet<>();
        private int executions;

        private ShapeEvidence(String shape) {
            this.shape = shape;
        }

        private void add(SqlTraceEntryDto statement) {
            executions++;
            if (rawTextHashes.size() < MAX_TRACKED_VARIANTS) {
                // Only the hash is kept: it proves two executions differed without retaining what differed.
                rawTextHashes.add(digest(statement.sql()));
            }
        }

        private int variantCount() {
            return rawTextHashes.size();
        }

        private String shape() {
            return shape;
        }

        private boolean isReportable() {
            return rawTextHashes.size() >= MIN_VARIANTS;
        }

        private String describe(int retainedStatements) {
            StringBuilder detail = new StringBuilder()
                    .append("Shape ")
                    .append(shape)
                    .append(" \u2014 ")
                    .append(executions)
                    .append(executions == 1 ? " execution, " : " executions, ")
                    .append(rawTextHashes.size())
                    .append(rawTextHashes.size() >= MAX_TRACKED_VARIANTS ? "+" : "")
                    .append(rawTextHashes.size() == 1 ? " distinct text" : " distinct texts")
                    .append(" containing predicate literals. Retained window: ")
                    .append(retainedStatements)
                    .append(" statements; at most 500 eligible shapes examined.");
            return detail.toString();
        }
    }
}
