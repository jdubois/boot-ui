package io.github.jdubois.bootui.engine.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SqlShapesTests {

    @AfterEach
    void clear() {
        SqlShapes.clear();
    }

    @Test
    void aStatementsShapeMatchesTheNormalizerAndTheTableParser() {
        String sql = "select * from orders o join lines l on l.order_id = o.id where o.id = 42";

        assertThat(SqlShapes.fingerprint(sql)).isEqualTo(SqlStatementNormalizer.fingerprintOf(sql));
        assertThat(SqlShapes.tables(sql)).isEqualTo(SqlTables.of(sql));
        assertThat(SqlShapes.fingerprint(sql)).isSameAs(SqlShapes.fingerprint(sql));
        assertThatThrownBy(() -> SqlShapes.tables(sql).add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void onlyAStatementWithoutAPredicateLiteralIsShareable() {
        assertThat(SqlShapes.shareable("select * from orders where id = ?")).isTrue();
        assertThat(SqlShapes.shareable("select * from orders order by id limit 10"))
                .isTrue();
        assertThat(SqlShapes.shareable("select * from orders where id = 42")).isFalse();
        assertThat(SqlShapes.shareable("select * from orders where name like 'a%'"))
                .isFalse();
        assertThat(SqlShapes.shareable(null)).isFalse();
    }

    @Test
    void theCacheIsBoundedByStatementsAndBytes() {
        for (int i = 0; i < SqlShapes.MAX_ENTRIES + 10; i++) {
            SqlShapes.fingerprint("select " + i + " from t" + i);
        }
        assertThat(SqlShapes.size()).isBetween(1, SqlShapes.MAX_ENTRIES);

        SqlShapes.clear();
        String padding = "x".repeat(SqlShapes.MAX_CACHED_LENGTH - 40);
        for (int i = 0; i < 200; i++) {
            SqlShapes.fingerprint("select '" + padding + "' from t" + i);
        }
        assertThat(SqlShapes.bytes()).isBetween(1L, SqlShapes.MAX_BYTES);

        SqlShapes.clear();
        String huge = "select '" + "x".repeat(SqlShapes.MAX_CACHED_LENGTH) + "' from t";
        assertThat(SqlShapes.fingerprint(huge)).isEqualTo(SqlStatementNormalizer.fingerprintOf(huge));
        assertThat(SqlShapes.size())
                .as("a statement too long to cache is parsed on every read")
                .isZero();
        assertThat(SqlShapes.fingerprint(null)).isEqualTo(SqlStatementNormalizer.fingerprintOf(null));
    }

    @Test
    void theBoundsHoldUnderConcurrentReads() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                done.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < SqlShapes.MAX_ENTRIES; i++) {
                        SqlShapes.tables("select * from t" + thread + "_" + i);
                        assertThat(SqlShapes.size()).isLessThanOrEqualTo(SqlShapes.MAX_ENTRIES);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : done) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(SqlShapes.size()).isBetween(1, SqlShapes.MAX_ENTRIES);
        assertThat(SqlShapes.bytes()).isBetween(1L, SqlShapes.MAX_BYTES);
    }

    @Test
    void clearingTheRecordingForgetsItsStatements() throws InterruptedException {
        RuntimeJournal journal = journal();
        try {
            offerSql(journal);
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
            assertThat(SqlShapes.size()).isPositive();

            journal.clear();

            assertThat(SqlShapes.size()).isZero();
        } finally {
            journal.close();
        }
    }

    @Test
    void closingTheJournalForgetsItsStatementsAfterProcessingTheQueuedOnes() {
        RuntimeJournal journal = journal();
        offerSql(journal);

        journal.close();

        assertThat(SqlShapes.size())
                .as("the last batch, processed on close, does not refill it")
                .isZero();
    }

    private static RuntimeJournal journal() {
        return new RuntimeJournal(
                new RuntimeJournalSettings(true, 1_000, 10_000_000, 1_000, 10, 10, JournalSource.all()),
                RunIdentity.start());
    }

    private static void offerSql(RuntimeJournal journal) {
        for (int i = 0; i < 20; i++) {
            journal.offer(RuntimeEvent.of(
                    JournalSource.SQL,
                    i,
                    1,
                    null,
                    "app-thread",
                    null,
                    false,
                    new SqlPayload("select * from orders where id = ? and n = " + i, null, "db", false)));
        }
    }
}
