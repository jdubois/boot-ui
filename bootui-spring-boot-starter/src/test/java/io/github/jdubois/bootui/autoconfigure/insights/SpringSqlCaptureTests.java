package io.github.jdubois.bootui.autoconfigure.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.insights.SqlCapture;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.reactive.GenericReactiveWebApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

class SpringSqlCaptureTests {

    /** Stands in for R2DBC's {@code ConnectionFactory}, which this module's tests do not depend on. */
    interface ConnectionFactory {}

    @Test
    void anR2dbcApplicationWithoutATracedDataSourceIsNamedAsSuch() {
        try (GenericReactiveWebApplicationContext context = new GenericReactiveWebApplicationContext()) {
            context.registerBean(SqlTraceRecorder.class, SpringSqlCaptureTests::recorder);
            context.registerBean(ConnectionFactory.class, () -> new ConnectionFactory() {});
            context.refresh();

            assertThat(new SpringSqlCapture(context, ConnectionFactory.class.getName()).get())
                    .isEqualTo(SqlCapture.notRecorded(SqlCapture.R2DBC_ONLY));
        }
    }

    @Test
    void aTracedDataSourceBesideR2dbcRecordsJdbcAndSaysWhatItMisses() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            SqlTraceRecorder recorder = recorder();
            recorder.registerDataSource("dataSource");
            context.registerBean(SqlTraceRecorder.class, () -> recorder);
            context.registerBean(ConnectionFactory.class, () -> new ConnectionFactory() {});
            context.refresh();

            assertThat(new SpringSqlCapture(context, ConnectionFactory.class.getName()).get())
                    .isEqualTo(SqlCapture.recordedExcept(SqlCapture.R2DBC_NOT_RECORDED));
            assertThat(new SpringSqlCapture(context).get())
                    .as("without R2DBC on the classpath, a traced DataSource records everything")
                    .isEqualTo(SqlCapture.capturing());
        }
    }

    @Test
    void withoutSqlTraceNothingIsRecorded() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();

            assertThat(new SpringSqlCapture(context).get()).isEqualTo(SqlCapture.notRecorded(SqlCapture.NOT_RECORDED));
        }
    }

    private static SqlTraceRecorder recorder() {
        return new SqlTraceRecorder(true, true, false, false, 8, 100, 2000, 200, 5);
    }
}
