package io.github.jdubois.bootui.autoconfigure.security;

import java.util.Set;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Field metadata for the security advisors' passive readers, not permission to invoke application
 * callbacks. Public-method hints do not retain private chain inventories in a native image.
 */
public final class SecurityRuntimeHints implements RuntimeHintsRegistrar {

    static final Set<String> FRAMEWORK_TYPES = Set.of(
            "org.springframework.security.web.access.intercept.AuthorizationFilter",
            "org.springframework.security.web.access.intercept.FilterSecurityInterceptor",
            "org.springframework.security.web.context.SecurityContextHolderFilter",
            "org.springframework.security.web.context.SecurityContextPersistenceFilter",
            "org.springframework.security.web.context.request.async.WebAsyncManagerIntegrationFilter",
            "org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter",
            "org.springframework.security.web.authentication.www.BasicAuthenticationFilter",
            "org.springframework.security.web.authentication.AnonymousAuthenticationFilter",
            "org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter",
            "org.springframework.security.web.authentication.ui.DefaultLoginPageGeneratingFilter",
            "org.springframework.security.web.authentication.ui.DefaultLogoutPageGeneratingFilter",
            "org.springframework.security.web.authentication.ui.DefaultResourcesFilter",
            "org.springframework.security.web.authentication.logout.LogoutFilter",
            "org.springframework.security.web.authentication.AuthenticationFilter",
            "org.springframework.security.web.authentication.preauth.x509.X509AuthenticationFilter",
            "org.springframework.security.web.csrf.CsrfFilter",
            "org.springframework.security.web.session.SessionManagementFilter",
            "org.springframework.security.web.session.ConcurrentSessionFilter",
            "org.springframework.security.web.session.DisableEncodeUrlFilter",
            "org.springframework.security.web.access.ExceptionTranslationFilter",
            "org.springframework.security.web.savedrequest.RequestCacheAwareFilter",
            "org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter",
            "org.springframework.security.web.header.HeaderWriterFilter",
            "org.springframework.security.web.transport.HttpsRedirectFilter",
            "org.springframework.security.web.access.channel.ChannelProcessingFilter",
            "org.springframework.security.web.debug.DebugFilter",
            "org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy",
            "org.springframework.security.web.authentication.session.SessionFixationProtectionStrategy",
            "org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy",
            "org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy",
            "org.springframework.security.web.authentication.session.ConcurrentSessionControlAuthenticationStrategy",
            "org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy",
            "org.springframework.security.web.csrf.CsrfAuthenticationStrategy",
            "org.springframework.security.web.header.writers.HstsHeaderWriter",
            "org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter",
            "org.springframework.security.web.header.writers.XContentTypeOptionsHeaderWriter",
            "org.springframework.security.web.header.writers.XXssProtectionHeaderWriter",
            "org.springframework.security.web.header.writers.CacheControlHeadersWriter",
            "org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter",
            "org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter",
            "org.springframework.security.web.header.writers.PermissionsPolicyHeaderWriter",
            "org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter",
            "org.springframework.security.web.header.writers.CrossOriginEmbedderPolicyHeaderWriter",
            "org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter",
            "org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter",
            "org.springframework.security.oauth2.server.resource.web.OAuth2ProtectedResourceMetadataFilter",
            "org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter",
            "org.springframework.security.oauth2.client.web.OAuth2AuthorizationCodeGrantFilter",
            "org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter");

    private static final String[] SHARED_TYPES = {
        "org.springframework.core.env.AbstractEnvironment",
        "org.springframework.core.env.MutablePropertySources",
        "org.springframework.core.env.PropertySource",
        "org.springframework.core.env.CommandLinePropertySource",
        "org.springframework.core.env.CommandLineArgs",
        "org.springframework.boot.origin.OriginTrackedValue",
        "org.springframework.boot.support.SystemEnvironmentPropertySourceEnvironmentPostProcessor$OriginAwareSystemEnvironmentPropertySource",
        "java.util.Collections$UnmodifiableMap"
    };

    private static final String[] SERVLET_TYPES = {
        "org.springframework.security.web.FilterChainProxy",
        "org.springframework.security.web.DefaultSecurityFilterChain",
        "org.springframework.security.config.annotation.web.configuration.WebSecurityConfiguration$CompositeFilterChainProxy",
        "org.springframework.security.config.annotation.web.configuration.WebMvcSecurityConfiguration$CompositeFilterChainProxy",
        "org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter",
        "org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter",
        "org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices",
        "org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices",
        "org.springframework.security.web.context.DelegatingSecurityContextRepository",
        "org.springframework.security.web.access.intercept.RequestMatcherDelegatingAuthorizationManager",
        "org.springframework.security.authorization.ObservationAuthorizationManager",
        "org.springframework.security.authorization.SingleResultAuthorizationManager",
        "org.springframework.security.authorization.AuthorizationDecision",
        "org.springframework.security.authentication.ProviderManager",
        "org.springframework.security.authentication.ObservationAuthenticationManager",
        "org.springframework.security.authentication.dao.AbstractUserDetailsAuthenticationProvider",
        "org.springframework.security.authentication.dao.DaoAuthenticationProvider",
        "org.springframework.security.crypto.password.DelegatingPasswordEncoder",
        "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder",
        "org.springframework.util.function.SingletonSupplier",
        "org.springframework.security.web.firewall.StrictHttpFirewall",
        "org.springframework.security.web.PortMapperImpl",
        "org.springframework.security.web.DefaultRedirectStrategy",
        "org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher",
        "org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher$HttpMethodRequestMatcher",
        "org.springframework.security.web.util.matcher.OrRequestMatcher",
        "org.springframework.security.web.util.matcher.AndRequestMatcher",
        "org.springframework.security.web.util.matcher.NegatedRequestMatcher",
        "org.springframework.web.util.pattern.PathPattern",
        "org.springframework.web.filter.CorsFilter",
        "org.springframework.web.cors.UrlBasedCorsConfigurationSource",
        "org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider",
        "org.springframework.security.oauth2.server.resource.authentication.OpaqueTokenAuthenticationProvider",
        "org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor",
        "org.springframework.security.authorization.method.AuthorizationManagerAfterMethodInterceptor",
        "org.springframework.boot.webmvc.actuate.endpoint.web.AbstractWebMvcEndpointHandlerMapping",
        "org.springframework.boot.webmvc.actuate.endpoint.web.WebMvcEndpointHandlerMapping",
        "org.springframework.boot.actuate.endpoint.web.EndpointMapping",
        "org.springframework.boot.actuate.endpoint.AbstractExposableEndpoint",
        "org.springframework.boot.actuate.endpoint.annotation.AbstractDiscoveredEndpoint",
        "org.springframework.boot.actuate.endpoint.web.annotation.DiscoveredWebEndpoint",
        "org.springframework.boot.actuate.endpoint.web.annotation.DiscoveredWebOperation",
        "org.springframework.boot.actuate.endpoint.EndpointId",
        "org.springframework.boot.actuate.endpoint.web.WebOperationRequestPredicate",
        "org.apache.catalina.core.ApplicationContextFacade",
        "org.apache.catalina.core.ApplicationContext"
    };

    private static final String[] REACTIVE_TYPES = {
        "org.springframework.security.web.server.MatcherSecurityWebFilterChain",
        "org.springframework.security.web.server.authentication.AuthenticationWebFilter",
        "org.springframework.security.oauth2.client.web.server.authentication.OAuth2LoginAuthenticationWebFilter",
        "org.springframework.security.config.web.server.ServerHttpSecurity$OAuth2LoginSpec$OidcSessionRegistryAuthenticationWebFilter",
        "org.springframework.security.web.server.transport.HttpsRedirectWebFilter",
        "org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher",
        "org.springframework.security.web.server.header.HttpHeaderWriterWebFilter",
        "org.springframework.security.web.server.header.CompositeServerHttpHeadersWriter",
        "org.springframework.security.web.server.header.StrictTransportSecurityServerHttpHeadersWriter",
        "org.springframework.security.web.server.header.ContentSecurityPolicyServerHttpHeadersWriter",
        "org.springframework.security.web.server.header.StaticServerHttpHeadersWriter",
        "org.springframework.web.cors.reactive.CorsWebFilter",
        "org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource"
    };

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // Spring 7 type hints include declared-method query metadata. The AOT ownership check
        // only queries the factory method; adding invocation categories would be unnecessary.
        hints.reflection()
                .registerTypeIfPresent(
                        classLoader, "io.github.jdubois.bootui.autoconfigure.BootUiSpringSecurityAutoConfiguration");
        register(hints, classLoader, SHARED_TYPES);
        register(hints, classLoader, SERVLET_TYPES);
        register(hints, classLoader, REACTIVE_TYPES);
        // Include concrete filters as well as their declaring superclasses: the reader walks
        // getDeclaredField up the hierarchy, including negative lookups on a concrete filter.
        register(hints, classLoader, FRAMEWORK_TYPES.toArray(String[]::new));
    }

    private static void register(RuntimeHints hints, ClassLoader classLoader, String[] types) {
        for (String type : types) {
            hints.reflection().registerTypeIfPresent(classLoader, type, MemberCategory.ACCESS_DECLARED_FIELDS);
        }
    }
}
