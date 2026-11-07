package example.orders.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class OrderService {

    public List<String> orders() {
        return List.of("order-1", "order-2");
    }

    public List<String> catalog() {
        return List.of("book", "pen");
    }
}
