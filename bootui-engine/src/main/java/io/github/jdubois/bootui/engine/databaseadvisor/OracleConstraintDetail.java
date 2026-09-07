package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * One Oracle constraint's {@code all_constraints} status: whether it is enabled, whether existing rows have
 * been validated against it, and whether Oracle generated its name automatically.
 *
 * @param constraintType {@code all_constraints.constraint_type}: {@code P} (primary key), {@code U} (unique),
 *     {@code R} (foreign key), {@code C} (check, which also covers a column-level {@code NOT NULL})
 * @param status {@code all_constraints.status}: {@code ENABLED} or {@code DISABLED}
 * @param validated {@code all_constraints.validated}: {@code VALIDATED} or {@code NOT VALIDATED} — a
 *     {@code ENABLE NOVALIDATE} checks new writes without certifying existing rows; {@code DISABLE VALIDATE}
 *     retains validation state and can restrict DML
 * @param systemGeneratedName {@code all_constraints.generated = 'GENERATED NAME'}: Oracle named this
 *     constraint itself (e.g. {@code SYS_C0012345}), most commonly a column-level {@code NOT NULL}
 * @param searchCondition {@code all_constraints.search_condition_vc}, the check expression text — used only
 *     to recognize Oracle's own system-generated {@code NOT NULL} check constraint (the search condition
 *     reads {@code "COLUMN" IS NOT NULL}), never to evaluate the condition itself or suppress invalid states
 */
record OracleConstraintDetail(
        String schema,
        String table,
        String constraintName,
        String constraintType,
        String status,
        String validated,
        boolean systemGeneratedName,
        String searchCondition,
        String indexOwner,
        String indexName,
        String deferrable,
        String deferred,
        String rely) {

    OracleConstraintDetail(
            String schema,
            String table,
            String constraintName,
            String constraintType,
            String status,
            String validated,
            boolean systemGeneratedName,
            String searchCondition) {
        this(
                schema,
                table,
                constraintName,
                constraintType,
                status,
                validated,
                systemGeneratedName,
                searchCondition,
                null,
                null,
                null,
                null,
                null);
    }

    String qualifiedTable() {
        return schema == null || schema.isBlank() ? table : schema + "." + table;
    }

    boolean enabled() {
        return "ENABLED".equalsIgnoreCase(status);
    }

    boolean validatedAgainstExistingRows() {
        return "VALIDATED".equalsIgnoreCase(validated);
    }

    boolean isCheck() {
        return "C".equalsIgnoreCase(constraintType);
    }

    boolean isForeignKey() {
        return "R".equalsIgnoreCase(constraintType);
    }

    /**
     * True for Oracle's own system-generated column-level {@code NOT NULL} check constraint: unnamed by the
     * user and whose search condition is exactly the {@code IS NOT NULL} test Oracle synthesizes for it. A
     * user-authored, merely-unnamed {@code CHECK (...)} constraint is also system-named but has a different
     * search condition, so it is not excluded by this.
     */
    boolean systemGeneratedNotNull() {
        if (!systemGeneratedName || !isCheck() || searchCondition == null) {
            return false;
        }
        String normalized = searchCondition.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.endsWith("IS NOT NULL") && !normalized.contains(" AND ") && !normalized.contains(" OR ");
    }

    String describeType() {
        return switch (constraintType == null ? "" : constraintType) {
            case "P" -> "primary key";
            case "U" -> "unique";
            case "R" -> "foreign key";
            case "C" -> "check";
            default -> "constraint";
        };
    }
}
