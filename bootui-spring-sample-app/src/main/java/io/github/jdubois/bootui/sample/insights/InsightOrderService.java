package io.github.jdubois.bootui.sample.insights;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * The work behind each Runtime Insights seed and its counterexample ({@code docs/PLAN-v2.md} M3-6). Each method does
 * one deliberate thing an observation counts, or the corrected version that it must not count.
 */
@Service
public class InsightOrderService {

    private final JdbcTemplate jdbc;
    private final InsightAuditWriter audit;
    private final InsightSeedTables tables;
    private final RestClient.Builder restClients;
    private final Environment environment;

    public InsightOrderService(
            JdbcTemplate jdbc,
            InsightAuditWriter audit,
            InsightSeedTables tables,
            RestClient.Builder restClients,
            Environment environment) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.tables = tables;
        this.restClients = restClients;
        this.environment = environment;
    }

    /** {@code repeated-selects}: the orders, then each order's lines with one statement per order. */
    public List<Map<String, Object>> ordersLineByLine() {
        List<Map<String, Object>> orders = jdbc.queryForList("select id, customer from insight_orders order by id");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> order : orders) {
            // JdbcTemplate's rows ignore the case of their column names.
            List<Map<String, Object>> lines = jdbc.queryForList(
                    "select sku, quantity from insight_order_lines where order_id = ?", order.get("id"));
            result.add(Map.of("order", order, "lines", lines));
        }
        return result;
    }

    /** The counterexample: the orders and their lines in one statement. */
    public List<Map<String, Object>> ordersJoined() {
        return jdbc.queryForList("select o.id, o.customer, l.sku, l.quantity from insight_orders o"
                + " join insight_order_lines l on l.order_id = o.id order by o.id");
    }

    /** {@code safe-method-dml}: reading an order also writes an audit row, on a GET. */
    public Map<String, Object> viewWithAudit(long id) {
        Map<String, Object> order = jdbc.queryForMap("select id, customer from insight_orders where id = ?", id);
        audit.record(id, "viewed");
        return order;
    }

    /**
     * {@code connections-per-request} and {@code split-transaction-writes}: the order's update, and its audit row in a
     * {@code REQUIRES_NEW} transaction that holds a second connection and commits on its own.
     */
    @Transactional
    public void confirm(long id) {
        jdbc.update("update insight_orders set customer = customer where id = ?", id);
        audit.recordInItsOwnTransaction(id, "confirmed");
    }

    /** The counterexample: the order and its audit row written in one transaction, on one connection. */
    @Transactional
    public void ship(long id) {
        jdbc.update("update insight_orders set customer = customer where id = ?", id);
        audit.record(id, "shipped");
    }

    /** {@code transaction-across-remote-call}: a remote price check while the order's transaction holds its connection. */
    @Transactional
    public String priceCheckInsideTransaction(long id) {
        jdbc.queryForMap("select id, total_cents from insight_orders where id = ?", id);
        return remotePrice();
    }

    /** The counterexample: the order is read and committed first, then the price is checked. */
    public String priceCheckAfterCommit(long id) {
        jdbc.queryForMap("select id, total_cents from insight_orders where id = ?", id);
        return remotePrice();
    }

    /** {@code proxy-bypass}: calls its own {@code @Transactional} method, so no transaction starts. */
    public void recalculate(long id) {
        applyDiscount(id);
    }

    /** The counterexample: the same discount through another bean's proxy. */
    public void recalculateThroughTheBean(long id) {
        audit.applyDiscount(id);
    }

    /** Applies a discount; transactional only when called through the proxy. */
    @Transactional
    public void applyDiscount(long id) {
        jdbc.update("update insight_orders set total_cents = total_cents - 1 where id = ?", id);
    }

    /** {@code errors-behind-2xx}: an import whose transaction rolls back, which the controller still answers with 200. */
    @Transactional
    public void importOrder(long id) {
        jdbc.update(
                "insert into insight_audit (id, order_id, action) values (?, ?, ?)", tables.nextId(), id, "imported");
        throw new InsightImportException("The seeded import of order " + id + " fails after its first write");
    }

    /** {@code anonymous-data-reach}: a debug reset that writes every order's total. */
    public int resetTotals() {
        return jdbc.update("update insight_orders set total_cents = 1000 * id");
    }

    /** {@code lazy-sql-after-handler}: a report whose lines are read only when the response is written. */
    public List<InsightOrderReport> lazyReport() {
        List<InsightOrderReport> report = new ArrayList<>();
        for (Long id : jdbc.queryForList("select id from insight_orders order by id", Long.class)) {
            report.add(new InsightOrderReport(id, jdbc));
        }
        return report;
    }

    private String remotePrice() {
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        return restClients
                .build()
                .get()
                .uri("http://localhost:" + port + "/api/sample/hello")
                .retrieve()
                .body(String.class);
    }

    /** The failure of the seeded import, which rolls back its transaction. */
    static final class InsightImportException extends RuntimeException {

        InsightImportException(String message) {
            super(message);
        }
    }
}
