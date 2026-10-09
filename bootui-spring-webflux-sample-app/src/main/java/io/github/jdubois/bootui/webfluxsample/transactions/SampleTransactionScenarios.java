package io.github.jdubois.bootui.webfluxsample.transactions;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction boundaries over the sample's blocking JDBC datasource, as a reactive application that keeps JDBC writes
 * them: {@code @Transactional} methods with a plain return type, run by Spring Boot's {@code DataSourceTransactionManager}
 * on whichever thread calls them, here a {@code boundedElastic} worker ({@link SampleTransactionsController}). None
 * changes the seeded notes: the rollback's update is undone with its transaction.
 */
@Service
public class SampleTransactionScenarios {

    static final long SLOW_TRANSACTION_MILLIS = 650;

    private final JdbcTemplate jdbcTemplate;

    public SampleTransactionScenarios(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public long commit() {
        return countNotes();
    }

    @Transactional(readOnly = true)
    public long slowCommit() {
        long count = countNotes();
        try {
            Thread.sleep(SLOW_TRANSACTION_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Transaction sample was interrupted", interrupted);
        }
        return count;
    }

    @Transactional
    public void rollBack() {
        jdbcTemplate.update(
                "update sample_note set body = ? where id = (select min(id) from sample_note)",
                "Changed by the transaction sample, then rolled back with its transaction.");
        throw new SampleTransactionRollbackException();
    }

    private long countNotes() {
        Long count = jdbcTemplate.queryForObject("select count(*) from sample_note", Long.class);
        return count == null ? 0L : count;
    }
}
