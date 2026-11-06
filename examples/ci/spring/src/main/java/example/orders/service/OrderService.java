package example.orders.service;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    public List<String> orders() {
        return List.of("order-1", "order-2");
    }

    public List<String> catalog() {
        return List.of("book", "pen");
    }
}
