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
            "delete from audit_log, payroll using audit_log join payroll on true",
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

    @Test
    void everyDmlStatementInABatchCountsButSeparatorsInValuesCommentsAndIdentifiersDoNot() {
        assertThat(SqlTables.writes("insert into \"audit;log\" values ('; delete from secrets');\n"
                        + "/* ; update secrets set a = 1 */ delete from payroll where id in (select id from employees);"
                        + "select * from products; insert into audit_log values ($tag$; delete from passwords$tag$);"
                        + "-- ; delete from secrets\n update stock set qty = ?"))
                .containsExactly(
                        new SqlTables.WriteTargets(java.util.Set.of("audit;log"), true),
                        new SqlTables.WriteTargets(java.util.Set.of("payroll"), true),
                        new SqlTables.WriteTargets(java.util.Set.of("audit_log"), true),
                        new SqlTables.WriteTargets(java.util.Set.of("stock"), true));
        assertThat(SqlTables.writes(null)).isEmpty();
        assertThat(SqlTables.writes(" ")).isEmpty();
    }

    @Test
    void truncatedBatchPreviewsRecoverLaterTargetsOnlyAsCandidates() {
        String head = "insert into audit_log values ('";
        String sql = head + "x".repeat(256 - head.length()) + "…;\n delete from payroll";
        assertThat(SqlTables.writes(sql))
                .containsExactly(
                        new SqlTables.WriteTargets(java.util.Set.of("audit_log"), false),
                        new SqlTables.WriteTargets(java.util.Set.of("payroll"), false));
        assertThat(SqlTables.writeTarget("delete from customer_or…")).isNull();
        assertThat(SqlTables.writes("delete from customer_or…"))
                .singleElement()
                .satisfies(targets -> assertThat(targets.exact()).isFalse());
    }

    @Test
    void uncertainCommentSyntaxNeverTurnsCommentedDmlIntoExactWrites() {
        for (String sql : new String[] {
            "insert into audit_log values (?) # ; delete from secrets;",
            "insert into audit_log values (?) /* outer /* inner */ ; delete from secrets; */",
            "insert into audit_log values (?) /* outer /* inner */ ; delete from secrets ' */",
            "update a /*! join secrets s on s.id = a.id */ set s.x = 1",
            "update a /*M! join secrets s on s.id = a.id */ set s.x = 1"
        }) {
            assertThat(SqlTables.writeTarget(sql)).as(sql).isNull();
            assertThat(SqlTables.writes(sql)).as(sql).isNotEmpty().allSatisfy(targets -> {
                assertThat(targets.exact()).isFalse();
            });
        }
        assertThat(SqlTables.writes("/* first */ insert into audit_log values ('# /* nested'); /* second */"))
                .containsExactly(new SqlTables.WriteTargets(java.util.Set.of("audit_log"), true));
    }

    @Test
    void deleteUsingRetainsTheRealTableAsACandidateInsteadOfProvingItsAliasWasWritten() {
        String sql = "delete from a using audit_log as a join products p on p.id = a.id";
        assertThat(SqlTables.writeTarget(sql)).isNull();
        assertThat(SqlTables.writes(sql)).singleElement().satisfies(targets -> {
            assertThat(targets.exact()).isFalse();
            assertThat(targets.tables()).contains("audit_log");
        });
        assertThat(SqlTables.writes("delete from \"a\" using \"audit_log\" as \"a\""))
                .singleElement()
                .satisfies(targets -> {
                    assertThat(targets.exact()).isFalse();
                    assertThat(targets.tables()).contains("audit_log");
                });
    }

    @Test
    void multiTableDmlRetainsEveryLexicalCandidateWithoutCallingItAProvenWrite() {
        assertThat(SqlTables.writes("delete from audit_log, payroll using audit_log join payroll on true"))
                .singleElement()
                .satisfies(targets -> {
                    assertThat(targets.exact()).isFalse();
                    assertThat(targets.tables()).contains("audit_log", "payroll");
                });
        assertThat(SqlTables.writes(
                        "delete from \"audit_log\", \"payroll\" using \"audit_log\" join \"payroll\" on true"))
                .singleElement()
                .satisfies(targets -> assertThat(targets.tables()).contains("audit_log", "payroll"));
        assertThat(SqlTables.writes("delete a, b from audit_log a join payroll b on true"))
                .singleElement()
                .satisfies(targets -> {
                    assertThat(targets.exact()).isFalse();
                    assertThat(targets.tables()).contains("audit_log", "payroll");
                });
        assertThat(SqlTables.writes("update audit_log a join payroll p on true set a.note = ?, p.amount = ?"))
                .singleElement()
                .satisfies(targets -> {
                    assertThat(targets.exact()).isFalse();
                    assertThat(targets.tables()).contains("audit_log", "payroll");
                });
        assertThat(SqlTables.writes("update audit_log, payroll set payroll.amount = ?"))
                .singleElement()
                .satisfies(targets -> assertThat(targets.tables()).contains("audit_log", "payroll"));
        assertThat(SqlTables.writes("delete from audit_log.*, payroll.* using audit_log join payroll on true"))
                .singleElement()
                .satisfies(targets -> {
                    assertThat(targets.exact()).isFalse();
                    assertThat(targets.tables()).contains("audit_log", "payroll");
                });
    }

    @Test
    void dialectModifiersAreNotTableCandidatesAndOptionalIntoFromHeadsRemainVisible() {
        for (String sql : new String[] {
            "update ignore payroll set amount = ?",
            "update low_priority payroll set amount = ?",
            "update only payroll set amount = ?",
            "update only \"payroll\" set amount = ?",
            "update top (10) payroll set amount = ?",
            "delete quick from payroll where id = ?",
            "insert payroll values (?)",
            "delete payroll where id = ?",
            "merge payroll using changes on payroll.id = changes.id when matched then update set amount = ?",
            "replace into payroll values (?)"
        }) {
            assertThat(SqlTables.writes(sql)).as(sql).singleElement().satisfies(targets -> {
                assertThat(targets.exact()).isFalse();
                assertThat(targets.tables())
                        .contains("payroll")
                        .doesNotContain("ignore", "low_priority", "top", "quick", "set");
            });
        }
    }
}
