package io.github.jdubois.bootui.sample.insights;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The application-event seeds of Runtime Insights ({@code docs/PLAN-v2.md} M4-8): a transactional listener whose
 * event is published outside any transaction, and an after-commit listener that writes, each beside its corrected
 * version. Never copy these listeners into an application.
 */
@Component
public class InsightOrderEvents {

    /** Published when an order's customer is notified. */
    public record OrderNotified(long orderId) {}

    /** Published when an order is archived. */
    public record OrderArchived(long orderId) {}

    /** Published when an order is restored. */
    public record OrderRestored(long orderId) {}

    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private final InsightAuditWriter audit;
    private final InsightSeedTables tables;

    public InsightOrderEvents(
            ApplicationEventPublisher events, JdbcTemplate jdbc, InsightAuditWriter audit, InsightSeedTables tables) {
        this.events = events;
        this.jdbc = jdbc;
        this.audit = audit;
        this.tables = tables;
    }

    /** {@code transactional-listener-skipped}: published with no transaction, so the listener never runs. */
    public void notifyWithoutTransaction(long id) {
        events.publishEvent(new OrderNotified(id));
    }

    /** The counterexample: the same event published inside a transaction, so the listener runs after its commit. */
    @Transactional
    public void notifyInTransaction(long id) {
        jdbc.update("update insight_orders set customer = customer where id = ?", id);
        events.publishEvent(new OrderNotified(id));
    }

    /** {@code after-commit-writes}: archived in a transaction whose after-commit listener writes in none of its own. */
    @Transactional
    public void archive(long id) {
        jdbc.update("update insight_orders set customer = customer where id = ?", id);
        events.publishEvent(new OrderArchived(id));
    }

    /** The counterexample: restored in a transaction whose after-commit listener writes in a new transaction. */
    @Transactional
    public void restore(long id) {
        jdbc.update("update insight_orders set customer = customer where id = ?", id);
        events.publishEvent(new OrderRestored(id));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void sendNotification(OrderNotified event) {
        // A real application would send an email here, once the order's changes are committed.
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void auditArchive(OrderArchived event) {
        jdbc.update(
                "insert into insight_audit (id, order_id, action) values (?, ?, ?)",
                tables.nextId(),
                event.orderId(),
                "archived");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void auditRestore(OrderRestored event) {
        audit.recordInItsOwnTransaction(event.orderId(), "restored");
    }
}
