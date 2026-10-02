package io.github.jdubois.bootui.sample.insights;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One seeded case for each Runtime Insights observation, and the counterexample it must not report ({@code
 * docs/PLAN-v2.md} M3-6). Each route is deliberately written the way the observation describes, so the panel has
 * something true to show; {@code scripts/insights-demo.sh} and the {@code runtime-insights-demo} browser test exercise
 * them. Never copy these routes into an application.
 */
@RestController
@RequestMapping("/api/insights")
public class InsightSeedController {

    private static final Logger log = LoggerFactory.getLogger(InsightSeedController.class);

    private final InsightOrderService orders;
    private final InsightOrderEvents events;

    public InsightSeedController(InsightOrderService orders, InsightOrderEvents events) {
        this.orders = orders;
        this.events = events;
    }

    @GetMapping("/orders")
    public List<Map<String, Object>> ordersLineByLine() {
        return orders.ordersLineByLine();
    }

    @GetMapping("/orders/joined")
    public List<Map<String, Object>> ordersJoined() {
        return orders.ordersJoined();
    }

    @GetMapping("/orders/{id}")
    public Map<String, Object> viewWithAudit(@PathVariable long id) {
        return orders.viewWithAudit(id);
    }

    @PostMapping("/orders/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable long id) {
        orders.confirm(id);
        return Map.of("order", id, "status", "confirmed");
    }

    @PostMapping("/orders/{id}/ship")
    public Map<String, Object> ship(@PathVariable long id) {
        orders.ship(id);
        return Map.of("order", id, "status", "shipped");
    }

    @GetMapping("/orders/{id}/price-check")
    public Map<String, Object> priceCheck(@PathVariable long id) {
        return Map.of("order", id, "price", String.valueOf(orders.priceCheckInsideTransaction(id)));
    }

    @GetMapping("/orders/{id}/price-check-after-commit")
    public Map<String, Object> priceCheckAfterCommit(@PathVariable long id) {
        return Map.of("order", id, "price", String.valueOf(orders.priceCheckAfterCommit(id)));
    }

    @PostMapping("/orders/{id}/recalculate")
    public Map<String, Object> recalculate(@PathVariable long id) {
        orders.recalculate(id);
        return Map.of("order", id, "status", "recalculated");
    }

    @PostMapping("/orders/{id}/recalculate-through-bean")
    public Map<String, Object> recalculateThroughTheBean(@PathVariable long id) {
        orders.recalculateThroughTheBean(id);
        return Map.of("order", id, "status", "recalculated");
    }

    /** Answers 200 even though the import rolled back: the error stays behind the success status. */
    @PostMapping("/orders/{id}/import")
    public Map<String, Object> importOrder(@PathVariable long id) {
        try {
            orders.importOrder(id);
            return Map.of("order", id, "status", "imported");
        } catch (InsightOrderService.InsightImportException ex) {
            log.error("Seeded import failed: {}", ex.getMessage());
            return Map.of("order", id, "status", "accepted");
        }
    }

    /** Spring MVC logs the unreadable body as a framework warning while answering 400. */
    @PostMapping("/orders")
    public Map<String, Object> create(@RequestBody Map<String, Object> order) {
        return Map.of("received", order.size());
    }

    @GetMapping("/orders/report")
    public List<InsightOrderReport> lazyReport() {
        return orders.lazyReport();
    }

    @PostMapping("/orders/{id}/notify")
    public Map<String, Object> notifyCustomer(@PathVariable long id) {
        events.notifyWithoutTransaction(id);
        return Map.of("order", id, "status", "notified");
    }

    @PostMapping("/orders/{id}/notify-in-transaction")
    public Map<String, Object> notifyCustomerInTransaction(@PathVariable long id) {
        events.notifyInTransaction(id);
        return Map.of("order", id, "status", "notified");
    }

    @PostMapping("/orders/{id}/archive")
    public Map<String, Object> archive(@PathVariable long id) {
        events.archive(id);
        return Map.of("order", id, "status", "archived");
    }

    @PostMapping("/orders/{id}/restore")
    public Map<String, Object> restore(@PathVariable long id) {
        events.restore(id);
        return Map.of("order", id, "status", "restored");
    }

    /** An anonymous debug endpoint that rewrites every order's total. */
    @PostMapping("/debug/reset-totals")
    public Map<String, Object> resetTotals() {
        return Map.of("reset", orders.resetTotals());
    }

    /**
     * Reports by name. Security protects {@code /api/insights/reports/payroll} exactly, but the handler matches the
     * name case-insensitively, so {@code /api/insights/reports/PAYROLL} reaches the payroll report anonymously.
     */
    @GetMapping("/reports/{name}")
    public ResponseEntity<Map<String, Object>> report(@PathVariable String name) {
        String report = name.toLowerCase(Locale.ROOT);
        return switch (report) {
            case "payroll" -> ResponseEntity.ok(Map.of("report", report, "rows", 12));
            case "summary" -> ResponseEntity.ok(Map.of("report", report, "rows", 3));
            default -> ResponseEntity.notFound().build();
        };
    }
}
