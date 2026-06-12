package io.github.jdubois.bootui.autoconfigure.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.SqlTraceGroupDto;
import io.github.jdubois.bootui.core.dto.SqlTraceQueryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceStatsDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlTraceStoreTests {

    private static BootUiProperties.SqlTrace config(int maxQueries) {
        BootUiProperties.SqlTrace config = new BootUiProperties.SqlTrace();
        config.setMaxQueries(maxQueries);
        return config;
    }

    private static SqlTraceQueryDto query(String category, long elapsed, boolean success, boolean slow, String sql) {
        return new SqlTraceQueryDto(
                0L,
                1_000L,
                "ds",
                "1",
                "PREPARED",
                category,
                false,
                0,
                elapsed,
                success,
                slow,
                success ? null : "boom",
                "main",
                List.of(sql),
                null);
    }

    @Test
    void evictsOldestEntriesWhenBufferIsFullAndReassignsIds() {
        SqlTraceStore store = new SqlTraceStore(config(3));

        for (int i = 1; i <= 5; i++) {
            store.add(query("SELECT", i, true, false, "stmt-" + i));
        }

        SqlTraceStore.Snapshot snapshot = store.snapshot();
        assertThat(snapshot.queries()).hasSize(3);
        assertThat(snapshot.captured()).isEqualTo(5);
        assertThat(snapshot.evicted()).isEqualTo(2);
        // The two oldest entries are gone; the survivors keep insertion order.
        assertThat(snapshot.queries().get(0).statements()).containsExactly("stmt-3");
        assertThat(snapshot.queries().get(2).statements()).containsExactly("stmt-5");
        // Ids are reassigned monotonically as entries are added.
        assertThat(snapshot.queries().get(0).id()).isEqualTo(3L);
        assertThat(snapshot.queries().get(2).id()).isEqualTo(5L);
    }

    @Test
    void clampsConfiguredCapacityToSafeBounds() {
        assertThat(new SqlTraceStore(config(0)).maxQueries()).isEqualTo(1);
        assertThat(new SqlTraceStore(config(100_000)).maxQueries()).isEqualTo(SqlTraceStore.HARD_MAX_QUERIES);
    }

    @Test
    void pausedStoreDropsExecutions() {
        SqlTraceStore store = new SqlTraceStore(config(10));
        store.setRecording(false);

        store.add(query("SELECT", 1, true, false, "select 1"));

        SqlTraceStore.Snapshot snapshot = store.snapshot();
        assertThat(snapshot.queries()).isEmpty();
        assertThat(snapshot.captured()).isZero();
    }

    @Test
    void clearRemovesEntriesAndResetsCounters() {
        SqlTraceStore store = new SqlTraceStore(config(2));
        for (int i = 1; i <= 4; i++) {
            store.add(query("SELECT", i, true, false, "stmt-" + i));
        }

        int cleared = store.clear();

        assertThat(cleared).isEqualTo(2);
        SqlTraceStore.Snapshot snapshot = store.snapshot();
        assertThat(snapshot.queries()).isEmpty();
        assertThat(snapshot.captured()).isZero();
        assertThat(snapshot.evicted()).isZero();
    }

    @Test
    void tracksWrappedDataSourceNames() {
        SqlTraceStore store = new SqlTraceStore(config(10));
        assertThat(store.hasWrappedDataSource()).isFalse();

        store.registerDataSource("primary");
        store.registerDataSource(null);
        store.registerDataSource("  ");
        store.registerDataSource("primary");

        assertThat(store.hasWrappedDataSource()).isTrue();
        assertThat(store.dataSourceNames()).containsExactly("primary");
    }

    @Test
    void computeStatsAggregatesByCategoryAndOutcome() {
        List<SqlTraceQueryDto> buffered = List.of(
                query("SELECT", 10, true, false, "select 1"),
                query("SELECT", 30, true, true, "select 2"),
                query("INSERT", 20, true, false, "insert into t"),
                query("UPDATE", 40, false, true, "update t"),
                query("DELETE", 0, true, false, "delete from t"),
                query("DDL", 0, true, false, "create table t"));

        SqlTraceStatsDto stats = SqlTraceStore.computeStats(buffered, 6, 0);

        assertThat(stats.recorded()).isEqualTo(6);
        assertThat(stats.captured()).isEqualTo(6);
        assertThat(stats.totalElapsedMillis()).isEqualTo(100);
        assertThat(stats.maxElapsedMillis()).isEqualTo(40);
        assertThat(stats.avgElapsedMillis()).isCloseTo(100.0 / 6, org.assertj.core.data.Offset.offset(0.001));
        assertThat(stats.slowQueries()).isEqualTo(2);
        assertThat(stats.failedQueries()).isEqualTo(1);
        assertThat(stats.selectCount()).isEqualTo(2);
        assertThat(stats.insertCount()).isEqualTo(1);
        assertThat(stats.updateCount()).isEqualTo(1);
        assertThat(stats.deleteCount()).isEqualTo(1);
        assertThat(stats.otherCount()).isEqualTo(1);
    }

    @Test
    void computeStatsHandlesEmptyBuffer() {
        SqlTraceStatsDto stats = SqlTraceStore.computeStats(List.of(), 0, 0);

        assertThat(stats.recorded()).isZero();
        assertThat(stats.avgElapsedMillis()).isZero();
        assertThat(stats.maxElapsedMillis()).isZero();
    }

    @Test
    void computeGroupsFlagsRepeatedSelectsAsPotentialNPlusOne() {
        String repeatedSelect = "select * from product where id = ?";
        List<SqlTraceQueryDto> buffered = new java.util.ArrayList<>();
        for (int i = 0; i < SqlTraceStore.N_PLUS_ONE_THRESHOLD; i++) {
            buffered.add(query("SELECT", 5, true, false, repeatedSelect));
        }
        for (int i = 0; i < 3; i++) {
            buffered.add(query("INSERT", 5, true, false, "insert into product values (?)"));
        }
        buffered.add(query("SELECT", 5, true, false, "select * from category"));

        List<SqlTraceGroupDto> groups = SqlTraceStore.computeGroups(buffered, SqlTraceStore.TOP_STATEMENTS_LIMIT);

        // Most frequent group first.
        SqlTraceGroupDto top = groups.get(0);
        assertThat(top.sql()).isEqualTo(repeatedSelect);
        assertThat(top.executions()).isEqualTo(SqlTraceStore.N_PLUS_ONE_THRESHOLD);
        assertThat(top.totalElapsedMillis()).isEqualTo(5L * SqlTraceStore.N_PLUS_ONE_THRESHOLD);
        assertThat(top.potentialNPlusOne()).isTrue();

        SqlTraceGroupDto inserts = groups.stream()
                .filter(g -> g.category().equals("INSERT"))
                .findFirst()
                .orElseThrow();
        // Repeated writes are not treated as N+1 reads.
        assertThat(inserts.potentialNPlusOne()).isFalse();

        SqlTraceGroupDto singleSelect = groups.stream()
                .filter(g -> g.sql().equals("select * from category"))
                .findFirst()
                .orElseThrow();
        assertThat(singleSelect.potentialNPlusOne()).isFalse();
    }

    @Test
    void computeGroupsRespectsLimit() {
        List<SqlTraceQueryDto> buffered = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            buffered.add(query("SELECT", 1, true, false, "select " + i));
        }

        List<SqlTraceGroupDto> groups = SqlTraceStore.computeGroups(buffered, 3);

        assertThat(groups).hasSize(3);
    }
}
