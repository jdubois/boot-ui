package io.github.jdubois.bootui.engine.restapi.fixtures.bad;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A controller that intentionally trips many REST API Advisor rules. */
@RestController
public class BadOrderController {

    // RAPI-NAME-001 (verb), RAPI-DTO-001 (entity), RAPI-PAGE-001 (unpaginated collection GET)
    @GetMapping("/getOrders")
    public List<OrderEntity> getOrders() {
        return List.of();
    }

    // RAPI-MAP-001 (no HTTP method on @RequestMapping)
    @RequestMapping("/legacy")
    public OrderDto legacy() {
        return new OrderDto();
    }

    // RAPI-RESP-001 (POST creation defaults to 200), RAPI-NAME-001 (verb),
    // RAPI-VALID-002 (entity request body / mass-assignment), RAPI-DTO-001 (entity response),
    // RAPI-VER-003 (wildcard media type)
    @PostMapping(value = "/createOrder", consumes = MediaType.ALL_VALUE)
    public OrderEntity createOrder(@RequestBody OrderEntity order) {
        return order;
    }

    // RAPI-MAP-005 (trailing slash), RAPI-NAME-003 (upper-case segment), RAPI-DTO-001 (entity)
    @GetMapping("/Orders/{id}/")
    public OrderEntity getOrder(@org.springframework.web.bind.annotation.PathVariable String id) {
        return new OrderEntity();
    }

    // RAPI-RESP-004 (GET returns bare scalar)
    @GetMapping("/count")
    public String count() {
        return "0";
    }

    // RAPI-ERR-002 (broad throws), RAPI-DTO-004 (mutable DTO with setters)
    @GetMapping("/find")
    public OrderDto find() throws Exception {
        return new OrderDto();
    }
}
