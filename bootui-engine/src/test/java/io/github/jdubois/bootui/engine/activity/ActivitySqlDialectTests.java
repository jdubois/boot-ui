package io.github.jdubois.bootui.engine.activity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ActivitySqlDialectTests {

    @Test
    void mysqlAndMariaDbUseLimitAndBigint() {
        for (String product : new String[] {"MySQL", "MariaDB", "mysql"}) {
            ActivitySqlDialect dialect = ActivitySqlDialect.detect(product);

            assertThat(dialect).as(product).isEqualTo(ActivitySqlDialect.MYSQL_FAMILY);
            assertThat(dialect.rowLimitClause()).isEqualTo(" LIMIT ?");
            assertThat(dialect.bigIntType()).isEqualTo("BIGINT");
        }
    }

    @Test
    void oracleUsesNumberForBigintAndTheStandardRowLimit() {
        ActivitySqlDialect dialect = ActivitySqlDialect.detect("Oracle");

        assertThat(dialect).isEqualTo(ActivitySqlDialect.ORACLE);
        assertThat(dialect.bigIntType()).isEqualTo("NUMBER(19)");
        assertThat(dialect.rowLimitClause()).isEqualTo(" OFFSET 0 ROWS FETCH FIRST ? ROWS ONLY");
    }

    @Test
    void everyOtherDatabaseUsesTheStandardForms() {
        for (String product : new String[] {"H2", "PostgreSQL", "Microsoft SQL Server", "Apache Derby", "", null}) {
            ActivitySqlDialect dialect = ActivitySqlDialect.detect(product);

            assertThat(dialect).as(String.valueOf(product)).isEqualTo(ActivitySqlDialect.STANDARD);
            assertThat(dialect.bigIntType()).isEqualTo("BIGINT");
            assertThat(dialect.rowLimitClause()).isEqualTo(" OFFSET 0 ROWS FETCH FIRST ? ROWS ONLY");
        }
    }
}
