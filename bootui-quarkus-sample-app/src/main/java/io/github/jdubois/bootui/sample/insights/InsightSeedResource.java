package io.github.jdubois.bootui.sample.insights;

import io.smallrye.common.annotation.NonBlocking;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Runtime Insights seeds on Quarkus ({@code docs/PLAN-v2.md} M3-6): one route per observation this stack records, each
 * beside the counterexample it must not report. It injects {@link DataSource}, which BootUI traces, rather than
 * {@code AgroalDataSource}, Agroal's own untraced pool. Security permits {@code /api/insights/*} and protects only {@code
 * /api/insights/reports/payroll}, case-sensitively, while the report route matches names case-insensitively. Never copy
 * these routes into an application.
 */
@Path("/api/insights")
@Produces(MediaType.APPLICATION_JSON)
public class InsightSeedResource {

    @Inject
    DataSource dataSource;

    @Inject
    InsightSeedTables tables;

    @Inject
    Event<InsightOrderEvents.OrderArchived> archived;

    @Inject
    InsightOrderEvents events;

    /** {@code repeated-selects}: the orders, then each order's lines with one statement per order. */
    @GET
    @Path("/orders")
    public List<Map<String, Object>> ordersLineByLine() throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (long id : orderIds(connection)) {
                try (PreparedStatement lines = connection.prepareStatement(
                        "select sku, quantity from insight_order_lines where order_id = ?")) {
                    lines.setLong(1, id);
                    result.add(Map.of("order", id, "lines", rows(lines.executeQuery())));
                }
            }
        }
        return result;
    }

    /** The counterexample: the orders and their lines in one statement. */
    @GET
    @Path("/orders/joined")
    public List<Map<String, Object>> ordersJoined() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement joined = connection.prepareStatement("select o.id, o.customer, l.sku, l.quantity"
                        + " from insight_orders o join insight_order_lines l on l.order_id = o.id order by o.id")) {
            return rows(joined.executeQuery());
        }
    }

    /** {@code event-loop-blocking}: a non-blocking route that still runs JDBC, on the Vert.x event loop. */
    @GET
    @Path("/orders/on-event-loop")
    @NonBlocking
    public List<Map<String, Object>> ordersOnTheEventLoop() throws SQLException {
        return ordersJoined();
    }

    /** {@code safe-method-dml}: reading an order also writes an audit row, on a GET. */
    @GET
    @Path("/orders/{id}")
    public Map<String, Object> viewWithAudit(@PathParam("id") long id) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            Map<String, Object> order;
            try (PreparedStatement read =
                    connection.prepareStatement("select id, customer from insight_orders where id = ?")) {
                read.setLong(1, id);
                List<Map<String, Object>> rows = rows(read.executeQuery());
                order = rows.isEmpty() ? Map.of() : rows.get(0);
            }
            try (PreparedStatement audit = connection.prepareStatement("insert into insight_audit values (?, ?, ?)")) {
                audit.setLong(1, tables.nextId());
                audit.setLong(2, id);
                audit.setString(3, "viewed");
                audit.executeUpdate();
            }
            return order;
        }
    }

    /** Fires an application event whose observer BootUI records with this request (M4-8). */
    @POST
    @Path("/orders/{id}/archive")
    public Map<String, Object> archive(@PathParam("id") long id) {
        archived.fire(new InsightOrderEvents.OrderArchived(id));
        return Map.of("order", id, "archived", events.lastArchived());
    }

    /** {@code anonymous-data-reach}: an anonymous debug endpoint that rewrites every order's total. */
    @POST
    @Path("/debug/reset-totals")
    public Map<String, Object> resetTotals() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement reset =
                        connection.prepareStatement("update insight_orders set total_cents = 1000 * id")) {
            return Map.of("reset", reset.executeUpdate());
        }
    }

    /**
     * {@code anonymous-success-on-restricted-route}: security protects {@code /api/insights/reports/payroll} exactly, but
     * this route matches the name case-insensitively, so {@code /api/insights/reports/PAYROLL} is anonymous.
     */
    @GET
    @Path("/reports/{name}")
    public Response report(@PathParam("name") String name) {
        String report = name.toLowerCase(Locale.ROOT);
        return switch (report) {
            case "payroll" -> Response.ok(Map.of("report", report, "rows", 12)).build();
            case "summary" -> Response.ok(Map.of("report", report, "rows", 3)).build();
            default -> Response.status(Response.Status.NOT_FOUND).build();
        };
    }

    private static List<Long> orderIds(Connection connection) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement orders = connection.prepareStatement("select id from insight_orders order by id");
                ResultSet rows = orders.executeQuery()) {
            while (rows.next()) {
                ids.add(rows.getLong(1));
            }
        }
        return ids;
    }

    private static List<Map<String, Object>> rows(ResultSet resultSet) throws SQLException {
        try (ResultSet rows = resultSet) {
            List<Map<String, Object>> result = new ArrayList<>();
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int column = 1; column <= columns; column++) {
                    row.put(rows.getMetaData().getColumnLabel(column).toLowerCase(Locale.ROOT), rows.getObject(column));
                }
                result.add(row);
            }
            return result;
        }
    }
}
