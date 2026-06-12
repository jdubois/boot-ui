package io.github.jdubois.bootui.autoconfigure.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.SqlTraceQueryDto;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.StatementType;
import org.junit.jupiter.api.Test;

class SqlTraceQueryListenerTests {

    private static SqlTraceStore store(boolean captureParameters) {
        BootUiProperties.SqlTrace config = new BootUiProperties.SqlTrace();
        config.setCaptureParameters(captureParameters);
        config.setSlowQueryThreshold(Duration.ofMillis(100));
        config.setMaxQueries(50);
        return new SqlTraceStore(config);
    }

    private static ExecutionInfo execution(
            StatementType type, long elapsed, boolean success, boolean batch, int batchSize, Throwable throwable) {
        ExecutionInfo info = new ExecutionInfo();
        info.setDataSourceName("ds");
        info.setConnectionId("conn-1");
        info.setStatementType(type);
        info.setElapsedTime(elapsed);
        info.setSuccess(success);
        info.setBatch(batch);
        info.setBatchSize(batchSize);
        info.setThrowable(throwable);
        return info;
    }

    @Test
    void recordsSelectExecutionWithMetadata() {
        SqlTraceStore store = store(false);
        SqlTraceQueryListener listener = new SqlTraceQueryListener(store);

        listener.afterQuery(
                execution(StatementType.PREPARED, 5, true, false, 0, null),
                List.of(new QueryInfo("select * from sample_products where active = ?")));

        SqlTraceQueryDto dto = store.snapshot().queries().get(0);
        assertThat(dto.category()).isEqualTo("SELECT");
        assertThat(dto.type()).isEqualTo("PREPARED");
        assertThat(dto.elapsedMillis()).isEqualTo(5);
        assertThat(dto.success()).isTrue();
        assertThat(dto.slow()).isFalse();
        assertThat(dto.dataSource()).isEqualTo("ds");
        assertThat(dto.connectionId()).isEqualTo("conn-1");
        assertThat(dto.thread()).isNotBlank();
        assertThat(dto.statements()).containsExactly("select * from sample_products where active = ?");
        // Parameter capture is disabled for this store.
        assertThat(dto.parameters()).isNull();
    }

    @Test
    void flagsSlowAndFailedExecutions() {
        SqlTraceStore store = store(false);
        SqlTraceQueryListener listener = new SqlTraceQueryListener(store);

        listener.afterQuery(
                execution(StatementType.STATEMENT, 250, false, false, 0, new SQLException("constraint violated")),
                List.of(new QueryInfo("update sample_products set active = true")));

        SqlTraceQueryDto dto = store.snapshot().queries().get(0);
        assertThat(dto.category()).isEqualTo("UPDATE");
        assertThat(dto.slow()).isTrue();
        assertThat(dto.success()).isFalse();
        assertThat(dto.error()).contains("constraint violated");
    }

    @Test
    void derivesCategoryFromLeadingKeywordIgnoringCommentsAndParentheses() {
        SqlTraceStore store = store(false);
        SqlTraceQueryListener listener = new SqlTraceQueryListener(store);

        listener.afterQuery(
                execution(StatementType.STATEMENT, 1, true, false, 0, null),
                List.of(new QueryInfo("insert into t (a) values (1)")));
        listener.afterQuery(
                execution(StatementType.STATEMENT, 1, true, false, 0, null),
                List.of(new QueryInfo("delete from t where a = 1")));
        listener.afterQuery(
                execution(StatementType.STATEMENT, 1, true, false, 0, null),
                List.of(new QueryInfo("create table t (a int)")));
        listener.afterQuery(
                execution(StatementType.STATEMENT, 1, true, false, 0, null),
                List.of(new QueryInfo("/* hint */ with cte as (select 1) select * from cte")));

        List<SqlTraceQueryDto> queries = store.snapshot().queries();
        assertThat(queries).extracting(SqlTraceQueryDto::category).containsExactly("INSERT", "DELETE", "DDL", "SELECT");
    }

    @Test
    void capturesParameterListWhenEnabled() {
        SqlTraceStore store = store(true);
        SqlTraceQueryListener listener = new SqlTraceQueryListener(store);

        listener.afterQuery(
                execution(StatementType.PREPARED, 1, true, false, 0, null), List.of(new QueryInfo("select 1")));

        SqlTraceQueryDto dto = store.snapshot().queries().get(0);
        // No bound parameters, but the list is present (not null) when capture is enabled.
        assertThat(dto.parameters()).isNotNull().isEmpty();
    }

    @Test
    void doesNotRecordWhenPaused() {
        SqlTraceStore store = store(false);
        store.setRecording(false);
        SqlTraceQueryListener listener = new SqlTraceQueryListener(store);

        listener.afterQuery(
                execution(StatementType.PREPARED, 1, true, false, 0, null), List.of(new QueryInfo("select 1")));

        assertThat(store.snapshot().queries()).isEmpty();
    }

    @Test
    void recordsBatchMetadata() {
        SqlTraceStore store = store(false);
        SqlTraceQueryListener listener = new SqlTraceQueryListener(store);

        listener.afterQuery(
                execution(StatementType.PREPARED, 3, true, true, 4, null),
                List.of(new QueryInfo("insert into t (a) values (?)")));

        SqlTraceQueryDto dto = store.snapshot().queries().get(0);
        assertThat(dto.batch()).isTrue();
        assertThat(dto.batchSize()).isEqualTo(4);
    }
}
