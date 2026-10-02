package io.github.jdubois.bootui.sample.insights;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates and fills the small order tables the Runtime Insights seeds read and write ({@code docs/PLAN-v2.md} M3-6),
 * with portable SQL on H2, PostgreSQL, and MySQL, and hands out their row ids. Runs once at startup, outside every
 * request, so none of its statements reaches an observation.
 */
@Component
public class InsightSeedTables implements ApplicationRunner {

    /** The orders seeded, enough for a per-order loop to repeat its statement five times. */
    static final int ORDERS = 6;

    private final JdbcTemplate jdbc;
    private final AtomicLong ids = new AtomicLong(1_000);

    public InsightSeedTables(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("create table if not exists insight_orders"
                + " (id bigint primary key, customer varchar(80) not null, total_cents bigint not null)");
        jdbc.execute(
                "create table if not exists insight_order_lines"
                        + " (id bigint primary key, order_id bigint not null, sku varchar(40) not null, quantity int not null)");
        jdbc.execute("create table if not exists insight_audit"
                + " (id bigint primary key, order_id bigint not null, action varchar(40) not null)");
        Long highest = jdbc.queryForObject(
                "select max(id) from (select max(id) as id from insight_orders union all"
                        + " select max(id) from insight_order_lines union all select max(id) from insight_audit) ids",
                Long.class);
        ids.set(Math.max(1_000, highest == null ? 0 : highest));
        Integer orders = jdbc.queryForObject("select count(*) from insight_orders", Integer.class);
        if (orders != null && orders > 0) {
            return;
        }
        for (int order = 1; order <= ORDERS; order++) {
            jdbc.update(
                    "insert into insight_orders (id, customer, total_cents) values (?, ?, ?)",
                    order,
                    "customer-" + order,
                    1_000L * order);
            for (int line = 1; line <= 2; line++) {
                jdbc.update(
                        "insert into insight_order_lines (id, order_id, sku, quantity) values (?, ?, ?, ?)",
                        nextId(),
                        order,
                        "SKU-" + order + "-" + line,
                        line);
            }
        }
    }

    /** A fresh row id for any of the seed tables. */
    long nextId() {
        return ids.incrementAndGet();
    }
}
