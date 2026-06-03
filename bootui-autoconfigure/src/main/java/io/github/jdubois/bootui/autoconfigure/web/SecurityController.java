package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import io.github.jdubois.bootui.core.dto.SecurityEndpointsReport;
import io.github.jdubois.bootui.core.dto.SecurityExplainDto;
import io.github.jdubois.bootui.core.dto.SecurityReport;

/**
 * Exposes Spring Security filter chain configuration for the BootUI developer console.
 *
 * <p>Read-only. Never surfaces credentials, signing keys, or session identifiers.
 * Activated only when {@code spring-security-web} is on the classpath.</p>
 */
@RestController
@ConditionalOnClass(FilterChainProxy.class)
@RequestMapping("/bootui/api/security")
public class SecurityController {

    private final SecurityService securityService;

    public SecurityController(
            ObjectProvider<FilterChainProxy> filterChainProxyProvider,
            ObjectProvider<AuthenticationProvider> authenticationProviderProvider,
            ObjectProvider<UserDetailsService> userDetailsServiceProvider,
            ObjectProvider<RequestMappingInfoHandlerMapping> handlerMappingProvider,
            Environment environment,
            BootUiProperties properties) {
        this(
                filterChainProxyProvider,
                authenticationProviderProvider,
                userDetailsServiceProvider,
                handlerMappingProvider,
                environment,
                properties,
                BootUiSelfDataFilter.defaults());
    }

    @Autowired
    public SecurityController(
            ObjectProvider<FilterChainProxy> filterChainProxyProvider,
            ObjectProvider<AuthenticationProvider> authenticationProviderProvider,
            ObjectProvider<UserDetailsService> userDetailsServiceProvider,
            ObjectProvider<RequestMappingInfoHandlerMapping> handlerMappingProvider,
            Environment environment,
            BootUiProperties properties,
            BootUiSelfDataFilter selfDataFilter) {
        this.securityService = new SecurityService(
                filterChainProxyProvider,
                authenticationProviderProvider,
                userDetailsServiceProvider,
                handlerMappingProvider,
                environment,
                properties,
                selfDataFilter);
    }

    @GetMapping
    public SecurityReport security() {
        return securityService.security();
    }

    @GetMapping("/explain")
    public SecurityExplainDto explain(
            @RequestParam(defaultValue = "GET") String method, @RequestParam(defaultValue = "/") String path) {
        return securityService.explain(method, path);
    }

    @GetMapping("/endpoints")
    public SecurityEndpointsReport endpoints() {
        return securityService.endpoints();
    }
}
