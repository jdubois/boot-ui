package io.github.jdubois.bootui.sample.insights;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

/**
 * The STOMP seed of Runtime Insights ({@code docs/PLAN-v2.md} M4-10): a message handler that loads orders line by line,
 * which {@code repeated-selects} reports under {@code consume websocket:/app/insights/rooms/{room}/orders} once each
 * message is an execution, and the joined counterexample beside it, which it must not report. Never copy these
 * handlers into an application.
 */
@Controller
public class InsightStompController {

    private final InsightOrderService orders;

    public InsightStompController(InsightOrderService orders) {
        this.orders = orders;
    }

    @MessageMapping("/insights/rooms/{room}/orders")
    @SendTo("/topic/insights")
    public int ordersLineByLine(@DestinationVariable String room) {
        return orders.ordersLineByLine().size();
    }

    @MessageMapping("/insights/rooms/{room}/orders-joined")
    @SendTo("/topic/insights")
    public int ordersJoined(@DestinationVariable String room) {
        return orders.ordersJoined().size();
    }
}
