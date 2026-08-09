package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL-specific: tables not using the InnoDB storage engine lose foreign-key enforcement, MVCC,
 * and crash-safe transactions (MyISAM/MEMORY/ARCHIVE tables are table-locked and non-transactional).
 */
final class MySqlNonInnodbEngineRule extends AbstractDatabaseAdvisorRule {

    MySqlNonInnodbEngineRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-MYSQL-001",
                "Tables not using the InnoDB storage engine",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects MySQL tables (information_schema.tables.ENGINE) whose storage engine is not " + "InnoDB.",
                "Convert the table to InnoDB (ALTER TABLE ... ENGINE=InnoDB). Non-InnoDB engines such as MyISAM do "
                        + "not enforce foreign keys, do not support transactions/MVCC, and use table-level locking, "
                        + "which surprises most JPA/Hibernate applications that assume ACID semantics.",
                "https://dev.mysql.com/doc/refman/8.0/en/innodb-introduction.html"));
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
            for (MySqlNonInnodbTable table : schema.mysqlNonInnodbTables()) {
                details.add(schema.dataSourceName() + ": table " + table.tableName() + " uses engine " + table.engine()
                        + " instead of InnoDB.");
            }
        }
        if (!anyMySql) {
            return skipped("No MySQL datasource was detected.");
        }
        return violation(details);
    }
}
