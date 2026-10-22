package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * MySQL's {@code performance_schema} is {@code OFF}, so statement digests and the rest of the server's own
 * instrumentation are not collected. Oracle MySQL only: on MariaDB it is off by default and the rule is skipped.
 */
final class MySqlPerformanceSchemaDisabledRule extends AbstractServerSettingRule {

    static final String ID = "DB-SERVER-007";
    static final String SETTING = "performance_schema";

    MySqlPerformanceSchemaDisabledRule() {
        super(
                new DatabaseAdvisorRuleDefinition(
                        ID,
                        "MySQL Performance Schema is disabled",
                        DatabaseAdvisorCategory.SERVER,
                        DatabaseAdvisorRuleSupport.LOW,
                        "Detects a MySQL server with performance_schema = OFF. It is on by default, so it was turned"
                                + " off on purpose or by a memory-saving configuration. Without it the server keeps"
                                + " no statement digests, wait or lock instrumentation, which is the evidence behind"
                                + " the MySQL panel's Statement ranking, Sessions and blocking and Index handler"
                                + " sections. The variable is read-only at runtime and the value is the one the scan"
                                + " connection's own session sees. MariaDB is skipped: it leaves the Performance"
                                + " Schema off by default and BootUI treats MariaDB as best effort.",
                        "Remove performance_schema=OFF from the server configuration and restart the server, unless"
                                + " the memory it needs (tens of megabytes) cannot be spared, in which case dismiss"
                                + " this finding. With it on, the MySQL panel's Statement ranking section lists the"
                                + " statements that take the most time.",
                        "https://dev.mysql.com/doc/refman/8.4/en/performance-schema-startup-configuration.html"),
                false,
                SETTING,
                5,
                5);
    }

    @Override
    Outcome scope(SchemaSnapshot schema) {
        if (schema.dialect() == Dialect.MARIADB) {
            return Outcome.skip("MariaDB leaves `performance_schema` off by default and is best effort here, so it is"
                    + " not evaluated.");
        }
        return super.scope(schema);
    }

    @Override
    Outcome assess(DatabaseAdvisorContext context, SchemaSnapshot schema, DatabaseFindings findings) {
        return judge(schema, findings);
    }

    @Override
    Boolean flagged(String value) {
        Boolean enabled = ServerSettings.bool(value);
        return enabled == null ? null : !enabled;
    }

    @Override
    String text(String dataSourceName, ServerSetting reported) {
        return "Datasource `" + dataSourceName + "`: " + SETTING + " is OFF: no statement digests or wait"
                + " instrumentation are collected.";
    }
}
