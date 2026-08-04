package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveSecurityController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveSpringSecurityController;
import io.github.jdubois.bootui.autoconfigure.security.SpringReactiveSecurityObservationCollector;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.reactivesecurity.ReactiveSecurityAdvisorService;
import io.github.jdubois.bootui.engine.reactivesecurity.ReactiveSecurityScanner;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Opens BootUI's own routes inside reactive Spring Security (WebFlux) while keeping the
 * localhost-only reactive safety filter as the outer security boundary.
 *
 * <p>This is the WebFlux twin of {@link BootUiSpringSecurityAutoConfiguration}. It creates a
 * high-precedence {@link SecurityWebFilterChain} that permits all requests to the BootUI UI and
 * API paths, configures SPA-compatible CSRF (cookie-based, readable by the Vue SPA), and exempts
 * the MCP JSON-RPC and OTLP ingest endpoints from CSRF protection — those are called by
 * programmatic clients (AI agents, OpenTelemetry exporters) that cannot present a CSRF token.
 * The exempted endpoints remain protected by BootUI's localhost-only reactive safety filter's
 * loopback, Host allow-list, and Origin/Sec-Fetch-Site checks.</p>
 *
 * <p>The BootUI localhost-only reactive safety filter remains the outer security boundary and
 * rejects non-loopback callers unless {@code bootui.allow-non-localhost=true} is set.</p>
 */
@AutoConfiguration(
        after = BootUiReactiveAutoConfiguration.class,
        afterName = "org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration")
@Conditional(BootUiActivationCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(
        name = {
            "org.springframework.security.config.web.server.ServerHttpSecurity",
            "org.springframework.security.web.server.SecurityWebFilterChain"
        })
@ConditionalOnBean(type = "org.springframework.security.config.web.server.ServerHttpSecurity")
@EnableConfigurationProperties(BootUiProperties.class)
@Import({ReactiveSpringSecurityController.class, ReactiveSecurityController.class})
public class BootUiReactiveSpringSecurityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BootUiReactiveSpringSecurityAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    ReactiveSecurityAdvisorService bootUiReactiveSecurityAdvisorService(
            ObjectProvider<SecurityWebFilterChain> filterChains,
            ObjectProvider<ListableBeanFactory> beanFactories,
            org.springframework.core.env.Environment environment,
            DismissedRulesStore dismissedRules) {
        SpringReactiveSecurityObservationCollector collector =
                new SpringReactiveSecurityObservationCollector(filterChains, beanFactories, environment);
        return new ReactiveSecurityAdvisorService(
                ReactiveSecurityScanner.using(collector::collect, Clock.systemUTC()), dismissedRules);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = "bootUiReactiveSecurityWebFilterChain")
    public SecurityWebFilterChain bootUiReactiveSecurityWebFilterChain(
            ServerHttpSecurity http, BootUiProperties properties) {
        String[] bootUiPatterns = bootUiSecurityPatterns(properties);
        log.warn(
                "BootUI detected reactive Spring Security and is permitting unauthenticated access"
                        + " to {}; BootUI's localhost-only filter still rejects non-loopback callers"
                        + " unless bootui.allow-non-localhost=true is set.",
                String.join(", ", bootUiPatterns));
        // Patterns for programmatic clients that cannot present a CSRF token.
        String otlpPattern = childSecurityPattern(properties.getApiPath(), "otlp");
        String mcpEndpoint = childSecurityEndpoint(properties.getApiPath(), "mcp");
        String mcpDescendantsPattern = childSecurityPattern(properties.getApiPath(), "mcp");
        String authSessionEndpoint = childSecurityEndpoint(properties.getApiPath(), "auth/session");
        var programmaticClientsMatcher = ServerWebExchangeMatchers.pathMatchers(
                otlpPattern, mcpEndpoint, mcpDescendantsPattern, authSessionEndpoint);
        // Preserve Spring Security's own safe-method definition and exempt only the bounded
        // programmatic-client paths. LocalhostGuard still runs outside Spring Security and rejects
        // cross-site writes on every BootUI route.
        var csrfRequiredMatcher = new AndServerWebExchangeMatcher(
                CsrfWebFilter.DEFAULT_CSRF_MATCHER, new NegatedServerWebExchangeMatcher(programmaticClientsMatcher));
        return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(bootUiPatterns))
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                // The SPA echoes the raw XSRF-TOKEN cookie in X-XSRF-TOKEN. The XOR handler expects
                // a masked browser-form token and rejects that cookie-to-header pattern, so use the
                // plain request-attribute handler and force token materialization below.
                .csrf(csrf -> csrf.csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(csrfRequiredMatcher))
                .addFilterAfter(new CsrfCookieWebFilter(), SecurityWebFiltersOrder.CSRF)
                .build();
    }

    static String[] bootUiSecurityPatterns(BootUiProperties properties) {
        String uiBasePath = withoutTrailingSlash(properties.getPath());
        String apiBasePath = withoutTrailingSlash(properties.getApiPath());
        return new String[] {uiBasePath, uiBasePath + "/**", apiBasePath, apiBasePath + "/**"};
    }

    private static String childSecurityPattern(String basePath, String childPath) {
        return childSecurityEndpoint(basePath, childPath) + "/**";
    }

    private static String childSecurityEndpoint(String basePath, String childPath) {
        return withoutTrailingSlash(basePath) + "/" + childPath;
    }

    private static String withoutTrailingSlash(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("BootUI path must not be blank");
        }
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * Reactive equivalent of the servlet {@code CsrfCookieFilter}: subscribing to the cached token
     * before continuing causes {@link CookieServerCsrfTokenRepository} to write the cookie on safe
     * reads, so the SPA can echo it on its next state-changing request.
     */
    private static final class CsrfCookieWebFilter implements WebFilter {

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            Object attribute = exchange.getAttribute(CsrfToken.class.getName());
            if (!(attribute instanceof Mono<?> token)) {
                return chain.filter(exchange);
            }
            return token.then(Mono.defer(() -> chain.filter(exchange)));
        }
    }
}
