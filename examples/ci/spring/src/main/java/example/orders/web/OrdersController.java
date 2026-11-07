package example.orders.web;

import example.orders.service.OrderService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrdersController {

    private final OrderService orders;

    public OrdersController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/orders")
    public List<String> orders() {
        return orders.orders();
    }

    @GetMapping("/catalog")
    public List<String> catalog() {
        return orders.catalog();
    }
}
