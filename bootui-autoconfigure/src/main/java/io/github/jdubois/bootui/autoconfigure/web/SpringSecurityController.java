package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.SpringSecurityEndpointsReport;
import io.github.jdubois.bootui.core.dto.SpringSecurityExplainDto;
import io.github.jdubois.bootui.core.dto.SpringSecurityReport;
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

/**
 * Exposes Spring Security filter chain configuration for the BootUI developer console.
 *
 * <p>Read-only. Never surfaces credentials, signing keys, or session identifiers.
 * Activated only when {@code spring-security-web} is on the classpath.</p>
 */
@RestController
@ConditionalOnClass(FilterChainProxy.class)
@RequestMapping("/bootui/api/spring-security")
public class SpringSecurityController {

    private final SpringSecurityService securityService;

    public SpringSecurityController(
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
    public SpringSecurityController(
            ObjectProvider<FilterChainProxy> filterChainProxyProvider,
            ObjectProvider<AuthenticationProvider> authenticationProviderProvider,
            ObjectProvider<UserDetailsService> userDetailsServiceProvider,
            ObjectProvider<RequestMappingInfoHandlerMapping> handlerMappingProvider,
            Environment environment,
            BootUiProperties properties,
            BootUiSelfDataFilter selfDataFilter) {
        this.securityService = new SpringSecurityService(
                filterChainProxyProvider,
                authenticationProviderProvider,
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
