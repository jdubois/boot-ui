package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Signals {@link ReactiveLiveActivityController} whenever a non-BootUI request completes, so its Live
 * Activity SSE stream can push a refresh tick and a new {@code REQUEST} row appears live in the panel.
 *
 * <p>This is the WebFlux replacement for the servlet controller's own
 * {@code @EventListener ServletRequestHandledEvent} method: Spring MVC publishes that event from its
 * {@code FrameworkServlet} after every dispatch, but WebFlux requests are never dispatched through that
 * servlet machinery, so no equivalent event exists to listen for. A dedicated, low-priority {@link
 * org.springframework.web.server.WebFilter} that observes every request completing is the closest
 * equivalent — {@link #shouldNotFilter} excludes BootUI's own traffic (its panel re-fetches and this SSE
 * connection itself) so the stream cannot trigger itself in a refresh loop, exactly like the servlet
 * controller's {@code isHostRequest} check.
 *
 * <p>Ordered last ({@link Ordered#LOWEST_PRECEDENCE}): unlike {@code ReactiveLocalhostOnlyFilter} or
 * {@code ReactivePanelAccessFilter}, this filter enforces no access policy, so it should never run ahead
 * of anything that might reject the request first.
 *
 * <p>Takes an {@link ObjectProvider} rather than a direct {@link ReactiveLiveActivityController}
 * dependency so the controller's own lazy-init (see {@code LAZY_CONTROLLER_CLASS_NAMES} in {@code
 * BootUiReactiveAutoConfiguration}) is preserved: this filter bean is always eagerly created (WebFlux
 * resolves every {@code WebFilter} bean up front to build the chain), so a direct constructor reference
 * would force the controller to be created at startup on every request path, not just once the panel is
 * actually opened. Signaling is a no-op anyway while nobody is listening (see {@code
 * ReactiveBootUiChangeStream#signal}), so resolving the provider lazily here is always safe.
 */
public class ReactiveActivitySignalFilter extends AbstractReactiveBootUiFilter implements Ordered {

    private final ObjectProvider<ReactiveLiveActivityController> controller;

    public ReactiveActivitySignalFilter(
            BootUiProperties properties, ObjectProvider<ReactiveLiveActivityController> controller) {
        super(properties);
        this.controller = controller;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    protected boolean shouldNotFilter(ServerWebExchange exchange) {
        return isBootUiRequest(exchange.getRequest());
    }

    @Override
    protected Mono<Void> doFilterInternal(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange).doFinally(signalType -> {
            ReactiveLiveActivityController instance = controller.getIfAvailable();
            if (instance != null) {
                instance.signalRequestHandled();
            }
        });
    }
}
