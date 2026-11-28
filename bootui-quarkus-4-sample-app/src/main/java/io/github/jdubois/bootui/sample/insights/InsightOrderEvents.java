package io.github.jdubois.bootui.sample.insights;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * The application event Runtime Insights records on Quarkus ({@code docs/PLAN-v2.md} M4-8): an order archived, and the
 * observer that audits it, which BootUI binds at build time and records with its request.
 */
@ApplicationScoped
public class InsightOrderEvents {

    /** Fired when an order is archived. */
    public record OrderArchived(long orderId) {}

    private volatile long lastArchived;

    void audit(@Observes OrderArchived event) {
        lastArchived = event.orderId();
    }

    /** The last order archived, so the observer's work is visible. */
    long lastArchived() {
        return lastArchived;
    }
}
