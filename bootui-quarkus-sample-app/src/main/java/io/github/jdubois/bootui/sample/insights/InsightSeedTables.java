package io.github.jdubois.bootui.sample.insights;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

/**
 * Creates and fills the small order tables the Runtime Insights seeds read and write ({@code docs/PLAN-v2.md} M3-6),
 * with portable SQL, and hands out their row ids. Runs once at startup, outside every request.
 */
@ApplicationScoped
public class InsightSeedTables {

    /** The orders seeded, enough for a per-order loop to repeat its statement five times. */
    static final int ORDERS = 6;

    @Inject
    DataSource dataSource;

    private final AtomicLong ids = new AtomicLong(1_000);

    void create(@Observes StartupEvent event) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists insight_orders"
                    + " (id bigint primary key, customer varchar(80) not null, total_cents bigint not null)");
            statement.execute("create table if not exists insight_order_lines (id bigint primary key,"
                    + " order_id bigint not null, sku varchar(40) not null, quantity int not null)");
            statement.execute("create table if not exists insight_audit"
                    + " (id bigint primary key, order_id bigint not null, action varchar(40) not null)");
            try (ResultSet highest = statement.executeQuery("select coalesce(max(id), 0) from insight_audit")) {
                highest.next();
                ids.set(Math.max(1_000, highest.getLong(1)));
            }
            try (ResultSet orders = statement.executeQuery("select count(*) from insight_orders")) {
                orders.next();
                if (orders.getInt(1) > 0) {
                    return;
                }
            }
            try (PreparedStatement order = connection.prepareStatement("insert into insight_orders values (?, ?, ?)");
                    PreparedStatement line =
                            connection.prepareStatement("insert into insight_order_lines values (?, ?, ?, ?)")) {
                for (int id = 1; id <= ORDERS; id++) {
                    order.setLong(1, id);
                    order.setString(2, "customer-" + id);
                    order.setLong(3, 1_000L * id);
                    order.executeUpdate();
                    for (int number = 1; number <= 2; number++) {
                        line.setLong(1, id * 100L + number);
                        line.setLong(2, id);
                        line.setString(3, "SKU-" + id + "-" + number);
                        line.setInt(4, number);
                        line.executeUpdate();
                    }
                }
            }
        }
    }

    /** A fresh row id for the audit table. */
    long nextId() {
        return ids.incrementAndGet();
    }
}
