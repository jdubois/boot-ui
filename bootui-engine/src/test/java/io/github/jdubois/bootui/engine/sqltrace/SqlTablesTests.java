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
        assertThat(SqlTables.of("select * from \"order-items\" join \"café\".\"orders\" on true"))
                .containsExactly("order-items", "café.orders");
    }

    @Test
    void namesNoTableForStatementsWithoutOne() {
        assertThat(SqlTables.of(null)).isEmpty();
        assertThat(SqlTables.of("select 1")).isEmpty();
        assertThat(SqlTables.of("select * from unnest(?)")).isEmpty();
        assertThat(SqlTables.of("select 1 from dual")).isEmpty();
    }

    @Test
    void ignoresTableLikeWordsInsideUnterminatedLiterals() {
        assertThat(SqlTables.of("select * from orders where note = $$join sëcrét"))
                .containsExactly("orders");
        assertThat(SqlTables.of("select * from orders where note = 'from sëcrét"))
                .containsExactly("orders");
    }

    @Test
    void identifiesOnlyTheWrittenTargetAndPreservesReadSideTableExtraction() {
        String insert = "insert into audit_log select id from products";
        assertThat(SqlTables.writeTarget(insert)).isEqualTo("audit_log");
        assertThat(SqlTables.of(insert)).containsExactly("audit_log", "products");
        assertThat(SqlTables.writeTarget("delete from cart_items where cart_id in (select id from carts)"))
                .isEqualTo("cart_items");
        assertThat(SqlTables.writeTarget("update orders set name = c.name from customers c where c.id = customer_id"))
                .isEqualTo("orders");
        assertThat(SqlTables.writeTarget(
                        "update orders o set name = c.name from customers c where c.id = o.customer_id"))
                .isEqualTo("orders");
        assertThat(
                        SqlTables.writeTarget(
                                "merge into stock using deliveries on stock.id = deliveries.id when matched then update set qty = ?"))
                .isEqualTo("stock");
        assertThat(
                        SqlTables.writeTarget(
                                "/* update secrets */ -- from passwords\n INSERT INTO \"Audit\".\"Events\" values ('from secrets')"))
                .isEqualTo("audit.events");
        assertThat(SqlTables.writeTarget("UPDATE `stock` SET count = ? WHERE id = ?"))
                .isEqualTo("stock");
        assertThat(SqlTables.writeTarget("delete from [dbo].[Carts] where id = ?"))
                .isEqualTo("dbo.carts");
    }

    @Test
    void uncertainHeadsNeverPromoteReadSourcesOrModifiersToWrittenTables() {
        for (String sql : new String[] {
            "select * from products",
            "with c as (select * from products) insert into audit_log select * from c",
            "update only orders set qty = ?",
            "update low_priority orders set qty = ?",
            "update ignore orders set qty = ?",
            "update top (5) orders set qty = ?",
            "delete top (5) from orders",
            "insert ignore into orders values (?)",
            "insert all into orders values (?) into audit values (?) select * from products",
            "replace into orders values (?)",
            "insert orders values (?)",
            "merge orders using products on orders.id = products.id when matched then update set qty = ?",
            "update t1 join t2 on t1.id = t2.id set t2.qty = ?",
            "update t1, t2 set t2.qty = ?",
            "update u set name = ? from users u join orders o on o.user_id = u.id",
            "update u set name = ? from users as u",
            "delete from u from users u join orders o on o.user_id = u.id",
            "update u set name = ? from orders o, users u where o.user_id = u.id",
            "delete from u from orders o, users u where o.user_id = u.id",
            "update u set name = ? from (select * from users) u",
            "update u set name = ? from orders o cross apply usersFor(o.id) u",
            "update \"u\" set name = ? from users \"u\"",
            "(insert into orders values (?))",
            "'insert into secret' select * from products"
        }) {
            assertThat(SqlTables.writeTarget(sql)).as(sql).isNull();
        }
        assertThat(SqlTables.writeTarget(null)).isNull();
        assertThat(SqlTables.writeTarget(" ")).isNull();
    }
}
