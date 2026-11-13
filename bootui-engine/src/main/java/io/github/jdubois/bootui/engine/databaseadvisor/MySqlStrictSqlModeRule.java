package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * A MySQL or MariaDB session whose {@code sql_mode} includes neither {@code STRICT_TRANS_TABLES} nor
 * {@code STRICT_ALL_TABLES}. Without strict transactional SQL mode, the server silently truncates over-long
 * strings, clamps out-of-range numbers, and substitutes implicit defaults for missing values instead of
 * rejecting invalid DML.
 */
final class MySqlStrictSqlModeRule extends AbstractServerSettingRule {

    static final String ID = "DB-SERVER-008";
    static final String SETTING = "sql_mode";

    MySqlStrictSqlModeRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        ID,
                        "MySQL/MariaDB strict transactional SQL mode is disabled",
                        DatabaseAdvisorCategory.SERVER,
                        DatabaseAdvisorRuleSupport.MEDIUM,
                        "Detects a MySQL or MariaDB server whose session sql_mode includes neither"
                                + " STRICT_TRANS_TABLES nor STRICT_ALL_TABLES. Both MySQL 8.0+ and MariaDB 10.2.4+"
                                + " enable STRICT_TRANS_TABLES by default; without a strict mode, invalid, missing,"
                                + " out-of-range or over-long values in DML statements can be silently truncated or"
                                + " coerced to implicit defaults instead of raising an error. The value is the one the"
                                + " scan connection's own session sees.",
                        "Restore STRICT_TRANS_TABLES (or STRICT_ALL_TABLES) in the server's sql_mode configuration"
                                + " and check that neither sessionVariables in the JDBC URL nor connectionInitSql on"
                                + " the pool strips strict mode from application connections.",
                        "https://dev.mysql.com/doc/refman/8.4/en/sql-mode.html#sql-mode-strict"),
                false,
                SETTING,
                0,
                0,
                "No MySQL or MariaDB datasource was detected.");
    }

    @Override
    Outcome scope(SchemaSnapshot schema) {
        return schema.dialect().isMySqlFamily() ? null : Outcome.notApplicable();
    }

    @Override
    Outcome assess(DatabaseAdvisorContext context, SchemaSnapshot schema, DatabaseFindings findings) {
        return judge(schema, findings);
    }

    @Override
    Boolean flagged(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return Boolean.TRUE;
        }
        boolean strict = false;
        for (String rawToken : trimmed.split(",", -1)) {
            String token = rawToken.trim();
            if (token.isEmpty() || !isIdentifierToken(token)) {
                return null;
            }
            if ("STRICT_TRANS_TABLES".equalsIgnoreCase(token) || "STRICT_ALL_TABLES".equalsIgnoreCase(token)) {
                strict = true;
            }
        }
        return !strict;
    }

    private static boolean isIdentifierToken(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean valid =
                    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9' && i > 0) || c == '_';
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    @Override
    String text(String dataSourceName, ServerSetting reported) {
        String raw = reported.value() == null ? "" : reported.value().trim();
        String shown = raw.isEmpty() ? "empty" : "`" + (raw.length() <= 64 ? raw : raw.substring(0, 64) + "...") + "`";
        return "Datasource `" + dataSourceName + "`: `sql_mode` is " + shown
                + " (neither `STRICT_TRANS_TABLES` nor `STRICT_ALL_TABLES` is enabled), so invalid or over-long"
                + " values can be coerced instead of rejected.";
    }
}
