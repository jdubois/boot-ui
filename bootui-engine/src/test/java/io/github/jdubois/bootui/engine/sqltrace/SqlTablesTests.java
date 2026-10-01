package io.github.jdubois.bootui.engine.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlTablesTests {

    @Test
    void readsTheTablesAStatementNamesAfterFromJoinIntoAndUpdate() {
        assertThat(SqlTables.of("select o.id from Orders o join order_lines l on l.order_id = o.id where o.id = ?"))
                .containsExactly("orders", "order_lines");
        assertThat(SqlTables.of("insert into \"Audit\".\"Events\" (id) values (?)"))
                .containsExactly("audit.events");
        assertThat(SqlTables.of("UPDATE `stock` SET count = ? WHERE id = ?")).containsExactly("stock");
        assertThat(SqlTables.of("delete from [dbo].[Carts] where id = ?")).containsExactly("dbo.carts");
        assertThat(SqlTables.of("select count(*) from (select id from orders) t"))
                .containsExactly("orders");
    }

    @Test
    void namesNoTableForStatementsWithoutOne() {
        assertThat(SqlTables.of(null)).isEmpty();
        assertThat(SqlTables.of("select 1")).isEmpty();
        assertThat(SqlTables.of("select * from unnest(?)")).isEmpty();
        assertThat(SqlTables.of("select 1 from dual")).isEmpty();
    }
}
