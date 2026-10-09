package io.github.jdubois.bootui.sample.insights;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EagerDemoOrderService {

    private final EagerDemoOrderRepository orders;

    public EagerDemoOrderService(EagerDemoOrderRepository orders) {
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public List<OrderSummary> listWithSecondarySelects() {
        return orders.findWithSecondarySelects().stream()
                .map(OrderSummary::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrderSummary> listWithCustomerJoin() {
        return orders.findWithCustomerJoin().stream().map(OrderSummary::from).toList();
    }

    public record OrderSummary(Long id, String description, String customer) {

        static OrderSummary from(EagerDemoOrder order) {
            return new OrderSummary(
                    order.getId(), order.getDescription(), order.getCustomer().getName());
        }
    }
}
