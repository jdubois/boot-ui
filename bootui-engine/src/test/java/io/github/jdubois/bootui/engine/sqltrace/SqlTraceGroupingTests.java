package io.github.jdubois.bootui.engine.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceGroupDto;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the grouping/N+1-flagging/call-site-aggregation helper shared by the per-request profile
 * (Spring's {@code LiveActivityCorrelator}, Quarkus's {@code RequestProfileAssembler}) and the list-level
 * N+1 badge (Spring's {@code LiveActivityService}, Quarkus's {@code LiveActivityAssembler}), so all four
 * consumers agree on exactly what counts as a potential N+1 access pattern.
 */
class SqlTraceGroupingTests {

    @Test
    void returnsEmptyListForNullOrEmptyInput() {
        assertThat(SqlTraceGrouping.group(null, 5)).isEmpty();
        assertThat(SqlTraceGrouping.group(List.of(), 5)).isEmpty();
    }

    @Test
    void groupsByExactSqlTextAndOrdersByExecutionCountDescending() {
        List<SqlTraceEntryDto> entries = List.of(
                sql("select 1", "SELECT", 1),
                sql("select 2", "SELECT", 2),
                sql("select 1", "SELECT", 3),
                sql("select 1", "SELECT", 4));

        List<SqlTraceGroupDto> groups = SqlTraceGrouping.group(entries, 5);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).sql()).isEqualTo("select 1");
        assertThat(groups.get(0).executions()).isEqualTo(3);
        assertThat(groups.get(1).sql()).isEqualTo("select 2");
        assertThat(groups.get(1).executions()).isEqualTo(1);
    }

    @Test
    void flagsRepeatedSelectsAtOrAboveThresholdAsPotentialNPlusOne() {
        List<SqlTraceEntryDto> fourExecutions = List.of(
                sql("select * from child where parent_id = ?", "SELECT", 1),
                sql("select * from child where parent_id = ?", "SELECT", 2),
                sql("select * from child where parent_id = ?", "SELECT", 3),
                sql("select * from child where parent_id = ?", "SELECT", 4));

        assertThat(SqlTraceGrouping.group(fourExecutions, 5).get(0).potentialNPlusOne())
                .isFalse();
        assertThat(SqlTraceGrouping.group(fourExecutions, 4).get(0).potentialNPlusOne())
                .isTrue();
    }

    @Test
    void neverFlagsNonSelectCategoriesRegardlessOfExecutionCount() {
        List<SqlTraceEntryDto> updates = List.of(
                sql("update parent set x = ?", "UPDATE", 1),
                sql("update parent set x = ?", "UPDATE", 2),
                sql("update parent set x = ?", "UPDATE", 3),
                sql("update parent set x = ?", "UPDATE", 4),
                sql("update parent set x = ?", "UPDATE", 5));

        assertThat(SqlTraceGrouping.group(updates, 5).get(0).potentialNPlusOne())
                .isFalse();
    }

    @Test
    void anySuspectedNPlusOneReflectsWhetherAnyGroupIsFlagged() {
        List<SqlTraceEntryDto> repeatedSelects = List.of(
                sql("select 1", "SELECT", 1),
                sql("select 1", "SELECT", 2),
                sql("select 1", "SELECT", 3),
                sql("select 1", "SELECT", 4),
                sql("select 1", "SELECT", 5));
        List<SqlTraceEntryDto> singleSelect = List.of(sql("select 1", "SELECT", 1));

        assertThat(SqlTraceGrouping.anySuspectedNPlusOne(repeatedSelects, 5)).isTrue();
        assertThat(SqlTraceGrouping.anySuspectedNPlusOne(singleSelect, 5)).isFalse();
        assertThat(SqlTraceGrouping.anySuspectedNPlusOne(List.of(), 5)).isFalse();
    }

    @Test
    void aggregatesDistinctCallSitesPerGroupInSuppliedOrder() {
        List<SqlTraceEntryDto> entries = List.of(
                sql("select 1", "SELECT", 1, "com.example.OrderRepository.findOne(OrderRepository.java:10)"),
                sql("select 1", "SELECT", 2, "com.example.OrderService.load(OrderService.java:20)"),
                sql("select 1", "SELECT", 3, "com.example.OrderRepository.findOne(OrderRepository.java:10)"),
                sql("select 1", "SELECT", 4, null));

        SqlTraceGroupDto group = SqlTraceGrouping.group(entries, 5).get(0);

        assertThat(group.callSites())
                .containsExactly(
                        "com.example.OrderRepository.findOne(OrderRepository.java:10)",
                        "com.example.OrderService.load(OrderService.java:20)");
    }

    @Test
    void boundsCallSitesPerGroupToMaxCallSitesPerGroup() {
        List<SqlTraceEntryDto> entries = new java.util.ArrayList<>();
        for (int i = 0; i < SqlTraceGrouping.MAX_CALL_SITES_PER_GROUP + 3; i++) {
            entries.add(sql("select 1", "SELECT", i, "com.example.Repo.method" + i + "(Repo.java:" + i + ")"));
        }

        SqlTraceGroupDto group = SqlTraceGrouping.group(entries, 5).get(0);

        assertThat(group.callSites()).hasSize(SqlTraceGrouping.MAX_CALL_SITES_PER_GROUP);
    }

    @Test
    void normalizesWhitespaceSoDifferentlyFormattedIdenticalSqlGroupsTogether() {
        List<SqlTraceEntryDto> entries =
                List.of(sql("select  *  from x", "SELECT", 1), sql("select * from x", "SELECT", 2));

        assertThat(SqlTraceGrouping.group(entries, 5)).hasSize(1);
        assertThat(SqlTraceGrouping.group(entries, 5).get(0).executions()).isEqualTo(2);
    }

    private static SqlTraceEntryDto sql(String sqlText, String category, long id) {
        return sql(sqlText, category, id, null);
    }

    private static SqlTraceEntryDto sql(String sqlText, String category, long id, String callSite) {
        return new SqlTraceEntryDto(
                id,
                id,
                sqlText,
                "PREPARED",
                category,
                1L,
                true,
                null,
                null,
                0,
                "c1",
                "worker-1",
                false,
                List.of(),
                null,
                callSite);
    }
}
