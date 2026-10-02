package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.journal.ControlMarkers;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.panel.BootUiGlobalWritePolicy;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.panel.BootUiPanels.Panel;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive (WebFlux) sibling of {@code PanelAccessFilter}: applies the same per-panel enabled and
 * read-only settings (from the shared {@link BootUiPanels} registry) to BootUI API routes, over a
 * {@link ServerWebExchange} instead of an {@code HttpServletRequest}/{@code HttpServletResponse} pair.
 */
public class ReactivePanelAccessFilter extends AbstractReactiveBootUiFilter implements Ordered {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private Supplier<RuntimeJournal> journal = () -> null;

    public ReactivePanelAccessFilter(BootUiProperties properties) {
        super(properties);
    }

    /**
     * Matches the servlet filter's {@code FilterRegistrationBean} order
     * ({@code Integer.MIN_VALUE + 3}), one after {@link ReactiveApiAuthenticationFilter}
     * ({@code Integer.MIN_VALUE + 2}). Must run after authentication so an unauthenticated remote
     * caller gets a 401 rather than a 403 that would leak panel-availability information.
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 3;
    }

    @Override
    protected boolean shouldNotFilter(ServerWebExchange exchange) {
        return !isBootUiApiRequest(exchange.getRequest());
    }

    @Override
    protected Mono<Void> doFilterInternal(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String apiRelativePath = apiRelativePath(request);
        String method = request.getMethod() != null ? request.getMethod().name() : "";
        if (!SAFE_METHODS.contains(method) && properties.isReadOnly()) {
            var globalSubject = BootUiGlobalWritePolicy.subjectFor(apiRelativePath);
            if (globalSubject.isPresent()) {
                return writeBlockedResponse(
                        exchange, globalSubject.get(), properties.panelReadOnlyReason(globalSubject.get()));
            }
        }

        Panel panel = apiRelativePath != null
                ? BootUiPanels.byApiPath(apiRelativePath).orElse(null)
                : null;
        if (panel == null) {
            return chain.filter(exchange);
        }

        if (!properties.isPanelEnabled(panel.id())) {
            return writeBlockedResponse(exchange, panel.id(), properties.panelDisabledReason(panel.id()));
        }

        if (panel.actionCapable() && !SAFE_METHODS.contains(method) && properties.isPanelReadOnly(panel.id())) {
            return writeBlockedResponse(exchange, panel.id(), properties.panelReadOnlyReason(panel.id()));
        }

        if (panel.actionCapable() && !SAFE_METHODS.contains(method)) {
            return chain.filter(exchange).then(Mono.fromRunnable(() -> {
                HttpStatusCode status = exchange.getResponse().getStatusCode();
                if (status == null || status.value() < 400) {
                    ControlMarkers.action(journal.get(), panel.id(), method, apiRelativePath);
                }
            }));
        }
        return chain.filter(exchange);
    }

    /** The journal a successful action is marked in ({@code docs/PLAN-v2.md} §5.18, M4-7). */
    public void setJournal(Supplier<RuntimeJournal> journal) {
        this.journal = journal == null ? () -> null : journal;
    }

    private String apiRelativePath(ServerHttpRequest request) {
        String path = pathWithinApplication(request);
        String apiPath = properties.getApiPath();
        if (path.equals(apiPath)) {
            return "/";
        }
        if (!path.startsWith(apiPath + "/")) {
            return null;
        }
        return path.substring(apiPath.length());
    }

    private Mono<Void> writeBlockedResponse(ServerWebExchange exchange, String panel, String reason) {
        String json = "{\"error\":\"" + escape("BootUI panel access denied") + "\",\"panel\":\"" + escape(panel)
                + "\",\"reason\":\"" + escape(reason) + "\"}";
        return writeJson(exchange, HttpStatus.FORBIDDEN, json);
    }
}
