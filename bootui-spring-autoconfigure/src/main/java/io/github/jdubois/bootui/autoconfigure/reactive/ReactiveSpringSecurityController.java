package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.SpringSecurityEndpointsReport;
import io.github.jdubois.bootui.core.dto.SpringSecurityExplainDto;
import io.github.jdubois.bootui.core.dto.SpringSecurityReport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;

/**
 * Reactive (WebFlux) sibling of {@code SpringSecurityController}: exposes the same
 * {@code /bootui/api/spring-security} API over a reactive {@link WebFilterChainProxy} /
 * {@link org.springframework.security.web.server.SecurityWebFilterChain} setup.
 *
 * <p>Activated only when {@code spring-security-web} is on the classpath (the
 * {@link WebFilterChainProxy} class is the distinguishing type for the reactive stack). Read-only.
 * Never surfaces credentials, signing keys, or session identifiers.</p>
 *
 * <p>Platform-aware reduced-fidelity notes:</p>
 * <ul>
 *   <li>Reactive filters ({@link org.springframework.web.server.WebFilter}) are named faithfully,
 *       but they differ from servlet {@code jakarta.servlet.Filter} instances in lifecycle and
 *       semantics.</li>
 *   <li>The {@code /explain} endpoint uses a best-effort path/method stub; header- and session-based
 *       matchers may not evaluate correctly ({@code bestEffort} is set when this is detected).</li>
 *   <li>Session management is indicated via {@code SecurityContextServerWebExchangeWebFilter}
 *       (which persists the security context to {@code WebSession}), not a separate
 *       {@code SessionManagementFilter} as on the servlet stack.</li>
 *   <li>The {@code /endpoints} listing is available when the application uses annotated
 *       WebFlux controllers ({@link RequestMappingHandlerMapping}); functional-style
 *       {@code RouterFunction} routes are not included.</li>
 * </ul>
 */
@RestController
@ConditionalOnClass(WebFilterChainProxy.class)
@RequestMapping("/bootui/api/spring-security")
public class ReactiveSpringSecurityController {

    private final ReactiveSpringSecurityService securityService;

    public ReactiveSpringSecurityController(
            ObjectProvider<WebFilterChainProxy> filterChainProxyProvider,
            ObjectProvider<ReactiveAuthenticationManager> authManagerProvider,
            ObjectProvider<ReactiveUserDetailsService> userDetailsServiceProvider,
            ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider,
            Environment environment,
            BootUiProperties properties) {
        this(
                filterChainProxyProvider,
                authManagerProvider,
                userDetailsServiceProvider,
                handlerMappingProvider,
                environment,
                properties,
                BootUiSelfDataFilter.defaults());
    }

    @Autowired
    public ReactiveSpringSecurityController(
            ObjectProvider<WebFilterChainProxy> filterChainProxyProvider,
            ObjectProvider<ReactiveAuthenticationManager> authManagerProvider,
            ObjectProvider<ReactiveUserDetailsService> userDetailsServiceProvider,
            ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider,
            Environment environment,
            BootUiProperties properties,
            BootUiSelfDataFilter selfDataFilter) {
        this.securityService = new ReactiveSpringSecurityService(
                filterChainProxyProvider,
                authManagerProvider,
                userDetailsServiceProvider,
                handlerMappingProvider,
                environment,
                properties,
                selfDataFilter);
    }

    @GetMapping
    public SpringSecurityReport security() {
        return securityService.security();
    }

    @GetMapping("/explain")
    public SpringSecurityExplainDto explain(
            @RequestParam(defaultValue = "GET") String method, @RequestParam(defaultValue = "/") String path) {
        return securityService.explain(method, path);
    }

    @GetMapping("/endpoints")
    public SpringSecurityEndpointsReport endpoints() {
        return securityService.endpoints();
    }
}
