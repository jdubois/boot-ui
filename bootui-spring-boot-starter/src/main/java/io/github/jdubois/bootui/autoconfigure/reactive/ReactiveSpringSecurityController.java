package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.SpringSecurityEndpointsReport;
import io.github.jdubois.bootui.core.dto.SpringSecurityExplainDto;
import io.github.jdubois.bootui.core.dto.SpringSecurityReport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Reactive (WebFlux) sibling of {@code SpringSecurityController}: exposes the same
 * {@code /bootui/api/spring-security} API over ordered reactive
 * {@link SecurityWebFilterChain} beans.
 *
 * <p>Activated only when {@code spring-security-web} is on the classpath. Read-only. Never
 * surfaces credentials, signing keys, sensitive matcher values, or session identifiers.</p>
 *
 * <p>Platform-aware reduced-fidelity notes:</p>
 * <ul>
 *   <li>Reactive filters ({@link org.springframework.web.server.WebFilter}) are named faithfully,
 *       but they differ from servlet {@code jakarta.servlet.Filter} instances in lifecycle and
 *       semantics.</li>
 *   <li>The {@code /explain} endpoint uses a sanitized path/method exchange; header-, cookie-,
 *       principal-, address-, body-, and session-based matchers are marked {@code bestEffort}.</li>
 *   <li>The legacy {@code sessionManagementPresent} wire field indicates reactive security-context
 *       integration. It does not claim that the chain persists a context in {@code WebSession}.</li>
 *   <li>The {@code /endpoints} listing is available when the application uses annotated
 *       WebFlux controllers ({@link RequestMappingHandlerMapping}); functional-style
 *       {@code RouterFunction} routes are not included.</li>
 * </ul>
 */
@RestController
@Lazy
@ConditionalOnClass(SecurityWebFilterChain.class)
@RequestMapping("${bootui.api-path:/bootui/api}/spring-security")
public class ReactiveSpringSecurityController {

    private final ReactiveSpringSecurityService securityService;

    public ReactiveSpringSecurityController(
            ObjectProvider<SecurityWebFilterChain> filterChainProvider,
            ObjectProvider<ReactiveAuthenticationManager> authManagerProvider,
            ObjectProvider<ReactiveUserDetailsService> userDetailsServiceProvider,
            ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider,
            Environment environment,
            BootUiProperties properties) {
        this(
                filterChainProvider,
                authManagerProvider,
                userDetailsServiceProvider,
                handlerMappingProvider,
                environment,
                properties,
                BootUiSelfDataFilter.defaults());
    }

    @Autowired
    public ReactiveSpringSecurityController(
            ObjectProvider<SecurityWebFilterChain> filterChainProvider,
            ObjectProvider<ReactiveAuthenticationManager> authManagerProvider,
            ObjectProvider<ReactiveUserDetailsService> userDetailsServiceProvider,
            ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider,
            Environment environment,
            BootUiProperties properties,
            BootUiSelfDataFilter selfDataFilter) {
        this.securityService = new ReactiveSpringSecurityService(
                filterChainProvider,
                authManagerProvider,
                userDetailsServiceProvider,
                handlerMappingProvider,
                environment,
                properties,
                selfDataFilter);
    }

    @GetMapping
    public Mono<SpringSecurityReport> security() {
        return securityService.security();
    }

    @GetMapping("/explain")
    public Mono<SpringSecurityExplainDto> explain(
            @RequestParam(defaultValue = "GET") String method,
            @RequestParam(defaultValue = "/") String path,
            ServerWebExchange exchange) {
        return securityService.explain(method, path, exchange);
    }

    @GetMapping("/endpoints")
    public Mono<SpringSecurityEndpointsReport> endpoints(ServerWebExchange exchange) {
        return securityService.endpoints(exchange);
    }
}
