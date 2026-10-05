package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.engine.safety.BootUiInternalMount;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive (WebFlux) sibling of {@code BootUiShellGuardFilter}: answers {@code 404} for the packaged
 * BootUI mount while BootUI is inactive.
 *
 * <p>WebFlux has the same exposure as Spring MVC. {@code WebFluxAutoConfiguration} serves
 * {@code classpath:/META-INF/resources/} by default, so the compiled Vue bundle shipped by
 * {@code bootui-ui} answers {@code GET /bootui/index.html} with {@code 200} even though no BootUI bean is
 * wired (#856). See {@code BootUiShellGuardFilter} for the full rationale.</p>
 *
 * <p>Both stacks agree on scope through the framework-neutral {@link BootUiInternalMount} predicate
 * rather than by one filter calling the other: a reactive-only application has no servlet API on its
 * classpath, so touching the servlet filter class from here would raise
 * {@code NoClassDefFoundError: jakarta/servlet/Filter} on every request.</p>
 *
 * <p>The path is resolved through {@link BootUiReactivePaths}, which rebuilds it from
 * {@code PathContainer.PathSegment#valueToMatch()} &mdash; what {@code PathPattern} matches on: it is
 * percent-decoded and has matrix parameters removed, so {@code /%62ootui/index.html} cannot slip past a
 * guard that the resource handler would then serve. The BootUI safety filters share that same helper.</p>
 */
public final class ReactiveBootUiShellGuardFilter implements WebFilter, Ordered {

    private final List<String> mounts;

    public ReactiveBootUiShellGuardFilter(List<String> mounts) {
        this.mounts = List.copyOf(mounts);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = BootUiReactivePaths.pathWithinApplication(exchange.getRequest());
        if (mounts.stream().noneMatch(mount -> BootUiInternalMount.isUnder(path, mount))) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        return exchange.getResponse().setComplete();
    }
}
