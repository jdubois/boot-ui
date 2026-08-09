package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL-specific: columns using a character set other than {@code utf8mb4} (e.g. the legacy {@code utf8}
 * alias, which is really a 3-byte encoding, or {@code latin1}) cannot store the full Unicode range —
 * including emoji and many CJK supplementary characters — and silently truncate or reject such input.
 */
final class MySqlNonUtf8mb4CharsetRule extends AbstractDatabaseAdvisorRule {

    MySqlNonUtf8mb4CharsetRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-MYSQL-002",
                "Tables/columns using a non-utf8mb4 character set",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Detects MySQL columns (information_schema.columns.CHARACTER_SET_NAME) whose character set is not "
                        + "utf8mb4.",
                "Convert the column (and ideally the table/database default) to utf8mb4 (ALTER TABLE ... CONVERT "
                        + "TO CHARACTER SET utf8mb4). The legacy utf8 alias in MySQL is actually a 3-byte encoding "
                        + "that cannot store the full Unicode range (emoji, many CJK supplementary characters), "
                        + "which surfaces as a silent truncation or an outright insert failure.",
                "https://dev.mysql.com/doc/refman/8.0/en/charset-unicode-utf8mb4.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        boolean anyMySql = false;
        for (SchemaSnapshot schema : context.availableSchemas()) {
            if (schema.dialect() != Dialect.MYSQL) {
                continue;
            }
            anyMySql = true;
            for (MySqlNonUtf8mb4Column column : schema.mysqlNonUtf8mb4Columns()) {
                details.add(schema.dataSourceName() + ": column " + column.tableName() + "." + column.columnName()
                        + " uses character set " + column.characterSetName() + " instead of utf8mb4.");
            }
        }
        if (!anyMySql) {
            return skipped("No MySQL datasource was detected.");
        }
        return violation(details);
    }
}
