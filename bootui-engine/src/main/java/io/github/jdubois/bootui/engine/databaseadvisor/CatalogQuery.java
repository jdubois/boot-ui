package io.github.jdubois.bootui.engine.databaseadvisor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs one bounded, read-only vendor catalog query and turns it into a {@link VendorAugmentation}.
 *
 * <p>Every query is bounded three ways: a {@code LIMIT ?} bound to {@code max + 1} (so exceeding the bound is
 * detected deterministically instead of silently returning a full-looking page), {@code setMaxRows} as a
 * driver-side backstop, and {@code setQueryTimeout} clamped to whatever is left of the scan budget. Failures
 * are captured as {@link VendorAugmentation.Status#FAILED} with a redacted reason rather than swallowed, so a
 * rule can say "the catalog refused" instead of reporting a clean result it never earned.</p>
 */
final class CatalogQuery {

    @FunctionalInterface
    interface RowMapper<T> {
        /** Maps the current row, or returns {@code null} to skip it. */
        T map(ResultSet resultSet) throws SQLException;
    }

    private CatalogQuery() {}

    static <T> VendorAugmentation<T> read(
            Connection connection,
            VendorFindingKind<T> kind,
            String sql,
            ScanBudget budget,
            DatabaseAdvisorLimits limits,
            RowMapper<T> mapper) {
        return read(connection, kind, sql, List.of(), budget, limits, mapper);
    }

    /**
     * Same as {@link #read(Connection, VendorFindingKind, String, ScanBudget, DatabaseAdvisorLimits,
     * RowMapper)}, for a query with extra {@code WHERE}-clause bind parameters ahead of the trailing row-limit
     * placeholder — Oracle's {@code ALL_*} queries always scope with {@code OWNER = ?} to stay inside the
     * connected session's {@code CURRENT_SCHEMA}, which a query text with no bind parameters cannot express.
     *
     * @param parameters bind values for every {@code ?} placeholder that appears before the final
     *     {@code FETCH FIRST ? ROWS ONLY}/{@code LIMIT ?} placeholder, in the same left-to-right order they
     *     appear in {@code sql}
     */
    static <T> VendorAugmentation<T> read(
            Connection connection,
            VendorFindingKind<T> kind,
            String sql,
            List<Object> parameters,
            ScanBudget budget,
            DatabaseAdvisorLimits limits,
            RowMapper<T> mapper) {
        if (budget.exhausted()) {
            return VendorAugmentation.failed(
                    kind, "The scan budget ran out before " + kind.label() + " could be read.");
        }
        int limit = limits.maxVendorFindings();
        List<T> findings = new ArrayList<>();
        boolean truncated = false;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(budget.remainingSecondsAtMost(limits.statementTimeoutSeconds()));
            statement.setMaxRows(limit + 1);
            int parameterIndex = 1;
            for (Object parameter : parameters) {
                statement.setObject(parameterIndex++, parameter);
            }
            statement.setInt(parameterIndex, limit + 1);
            if (budget.exhausted()) {
                return VendorAugmentation.failed(
                        kind, "The scan budget ran out before " + kind.label() + " could be read.");
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                int rows = 0;
                while (resultSet.next()) {
                    if (++rows > limit || budget.exhausted()) {
                        truncated = true;
                        break;
                    }
                    T finding = mapper.map(resultSet);
                    if (finding != null) {
                        findings.add(finding);
                    }
                }
            }
        } catch (SQLException | RuntimeException ex) {
            return VendorAugmentation.failed(kind, describe(kind, ex));
        }
        return VendorAugmentation.available(kind, findings, truncated);
    }

    private static String describe(VendorFindingKind<?> kind, Exception ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return kind.label() + " could not be read: "
                + io.github.jdubois.bootui.engine.support.CredentialRedaction.redact(message);
    }
}
