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

    boolean isForeignKey() {
        return "R".equalsIgnoreCase(constraintType);
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
