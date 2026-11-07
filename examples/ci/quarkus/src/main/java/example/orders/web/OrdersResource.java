package example.orders.web;

import example.orders.service.OrderService;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.List;

@Path("/")
public class OrdersResource {

    private final OrderService orders;

    public OrdersResource(OrderService orders) {
        this.orders = orders;
    }

    @GET
    @Path("orders")
    public List<String> orders() {
        return orders.orders();
    }

    @GET
    @Path("catalog")
    public List<String> catalog() {
        return orders.catalog();
    }
}
