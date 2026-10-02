package io.github.jdubois.bootui.sample.insights;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes the seeds' audit rows and discounts through the bean's proxy, as the seeds and counterexamples need. */
@Service
public class InsightAuditWriter {

    private final JdbcTemplate jdbc;
    private final InsightSeedTables tables;

    public InsightAuditWriter(JdbcTemplate jdbc, InsightSeedTables tables) {
        this.jdbc = jdbc;
        this.tables = tables;
    }

    /** An audit row in its own transaction, committed whatever the caller's transaction does. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInItsOwnTransaction(long orderId, String action) {
        record(orderId, action);
    }

    /** An audit row in the caller's transaction, or autocommitted without one. */
    public void record(long orderId, String action) {
        jdbc.update(
                "insert into insight_audit (id, order_id, action) values (?, ?, ?)", tables.nextId(), orderId, action);
    }

    /** A discount applied through the bean's proxy, so its transaction starts: proxy-bypass's counterexample. */
    @Transactional
    public void applyDiscount(long orderId) {
        jdbc.update("update insight_orders set total_cents = total_cents - 1 where id = ?", orderId);
    }
}
