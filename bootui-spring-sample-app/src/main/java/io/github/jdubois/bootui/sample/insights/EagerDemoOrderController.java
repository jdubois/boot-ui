package io.github.jdubois.bootui.sample.insights;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/insights/eager-orders")
public class EagerDemoOrderController {

    private final EagerDemoOrderService orders;

    public EagerDemoOrderController(EagerDemoOrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    public List<EagerDemoOrderService.OrderSummary> listWithSecondarySelects() {
        return orders.listWithSecondarySelects();
    }

    @GetMapping("/joined")
    public List<EagerDemoOrderService.OrderSummary> listWithCustomerJoin() {
        return orders.listWithCustomerJoin();
    }
}
