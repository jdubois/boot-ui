package example.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import example.orders.service.OrderService;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderServiceTest {

    @Test
    void ordinaryLocalWorkload() {
        assertEquals(List.of("order-1", "order-2"), new OrderService().orders());
    }
}
