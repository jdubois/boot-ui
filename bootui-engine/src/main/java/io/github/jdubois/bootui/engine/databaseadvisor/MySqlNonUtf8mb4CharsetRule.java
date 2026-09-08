package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MySQL/MariaDB-specific: a table default or a column still using the legacy three-byte {@code utf8}
 * (a.k.a. {@code utf8mb3}) encoding, which cannot store the full Unicode range — emoji and many CJK
 * supplementary characters are truncated or rejected outright.
 *
 * <p>Only {@code utf8}/{@code utf8mb3} is reported. Every other non-{@code utf8mb4} character set the
 * previous version flagged ({@code latin1}, {@code ascii}, {@code binary}, {@code ucs2}, ...) is almost
 * always a deliberate choice for a specific column, and reporting each one turned this rule into a wall of
 * noise on any legacy schema. {@code utf8mb3} is different: it is the trap MySQL created by naming a
 * three-byte encoding "utf8", so a developer who asked for Unicode did not get it.</p>
 *
 * <p>Collation names alone do not establish equivalent comparison semantics across vendors.</p>
 */
final class MySqlNonUtf8mb4CharsetRule extends AbstractDatabaseAdvisorRule {

    private static final Set<String> LEGACY_UTF8_CHARSETS = Set.of("utf8", "utf8mb3");

    MySqlNonUtf8mb4CharsetRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-MYSQL-002",
                "Tables/columns using the legacy utf8mb3 character set",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Detects MySQL/MariaDB table defaults (information_schema.tables.TABLE_COLLATION) and columns "
                        + "(information_schema.columns.CHARACTER_SET_NAME) using utf8/utf8mb3. Other legacy "
                        + "character sets such as latin1 or ascii are treated as deliberate and are not reported.",
                "If supplementary Unicode characters are required, plan a tested utf8mb4 conversion with a "
                        + "server-supported collation preserving required comparison and uniqueness semantics. "
                        + "Changing only the table default does not convert existing columns. Review key lengths, "
                        + "foreign-key compatibility, possible duplicate comparisons, rewrite cost and locks.",
                "https://dev.mysql.com/doc/refman/8.0/en/charset-unicode-utf8mb4.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.mySqlFamilySchemas();
        String skipReason = VendorRuleSupport.skipReason(
                context,
                definition().id(),
                schemas,
                VendorFindingKinds.MYSQL_COLUMN_CHARSETS,
                "No MySQL or MariaDB datasource was detected.");
        if (skipReason != null
                && schemas.stream()
                        .noneMatch(schema -> VendorRuleSupport.available(schema, VendorFindingKinds.MYSQL_TABLES))) {
            return skipped(skipReason);
        }
        List<String> details = new ArrayList<>();
        int eligible = 0;
        for (SchemaSnapshot schema : schemas) {
            VendorRuleSupport.coverage(
                    context,
                    definition().id(),
                    schema,
                    VendorFindingKinds.MYSQL_TABLES,
                    VendorFindingKinds.MYSQL_COLUMN_CHARSETS);
            for (MySqlTableInfo table : schema.vendorFindings().findings(VendorFindingKinds.MYSQL_TABLES)) {
                if (table.characterSet() == null) {
                    unknown(context, table.qualifiedName() + ": table default character set is unknown.");
                } else {
                    eligible++;
                }
            }
            for (MySqlColumnCharset column :
                    schema.vendorFindings().findings(VendorFindingKinds.MYSQL_COLUMN_CHARSETS)) {
                if (column.characterSet() == null) {
                    unknown(context, column.qualifiedColumn() + ": character set is unknown.");
                } else {
                    eligible++;
                }
            }
            collectTableDefaults(schema, details);
            collectColumns(schema, details);
        }
        return VendorRuleSupport.assessed(this, context, eligible, details);
    }

    private void collectTableDefaults(SchemaSnapshot schema, List<String> details) {
        if (!VendorRuleSupport.available(schema, VendorFindingKinds.MYSQL_TABLES)) {
            return;
        }
        for (MySqlTableInfo table : schema.vendorFindings().findings(VendorFindingKinds.MYSQL_TABLES)) {
            if (isLegacyUtf8(table.characterSet())) {
                details.add(schema.dataSourceName() + ": table " + table.qualifiedName()
                        + " defaults to character set " + table.characterSet() + " (collation "
                        + table.collation() + ") instead of utf8mb4. " + recommendedCollation(schema));
            }
        }
    }

    private void collectColumns(SchemaSnapshot schema, List<String> details) {
        if (!VendorRuleSupport.available(schema, VendorFindingKinds.MYSQL_COLUMN_CHARSETS)) {
            return;
        }
        for (MySqlColumnCharset column : schema.vendorFindings().findings(VendorFindingKinds.MYSQL_COLUMN_CHARSETS)) {
            if (isLegacyUtf8(column.characterSet())) {
                details.add(schema.dataSourceName() + ": column " + column.qualifiedColumn()
                        + " uses character set " + column.characterSet()
                        + ", a three-byte encoding that cannot store the full Unicode range. "
                        + recommendedCollation(schema));
            }
        }
    }

    private boolean isLegacyUtf8(String characterSet) {
        return characterSet != null && LEGACY_UTF8_CHARSETS.contains(characterSet.toLowerCase(Locale.ROOT));
    }

    private String recommendedCollation(SchemaSnapshot schema) {
        if (schema.dialect() == Dialect.MARIADB) {
            return "Review MariaDB comparison semantics: utf8mb4_uca1400_ai_ci (10.10+); 0900 aliases (11.4.5+). "
                    + "Verify server support.";
        }
        return "MySQL 8.0+ supports utf8mb4_0900_ai_ci; choose a supported collation matching required comparison semantics.";
    }
}
