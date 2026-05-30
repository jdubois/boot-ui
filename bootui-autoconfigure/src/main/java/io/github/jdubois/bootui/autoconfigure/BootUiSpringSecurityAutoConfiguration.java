package io.github.jdubois.bootui.autoconfigure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Opens BootUI's own route inside Spring Security while keeping the localhost-only
 * servlet filter as the outer safety boundary.
 */
@AutoConfiguration(
        afterName = "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration")
@Conditional(BootUiActivationCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(
        name = {
            "org.springframework.security.config.annotation.web.builders.HttpSecurity",
            "org.springframework.security.web.SecurityFilterChain"
        })
@ConditionalOnBean(HttpSecurity.class)
@EnableConfigurationProperties(BootUiProperties.class)
public class BootUiSpringSecurityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BootUiSpringSecurityAutoConfiguration.class);

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = "bootUiSecurityFilterChain")
    public SecurityFilterChain bootUiSecurityFilterChain(HttpSecurity http, BootUiProperties properties)
            throws Exception {
        String uiPattern = securityPattern(properties.getPath());
        String apiPattern = securityPattern(properties.getApiPath());
        String otlpPattern = childSecurityPattern(properties.getApiPath(), "otlp");
        log.warn(
                "BootUI detected Spring Security and is permitting unauthenticated access to {} and {}; "
                        + "BootUI's localhost-only filter still rejects non-loopback callers unless "
                        + "bootui.allow-non-localhost=true is set.",
                uiPattern,
                apiPattern);
        return http.securityMatcher(uiPattern, apiPattern)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(csrf -> csrf.spa().ignoringRequestMatchers(otlpPattern))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .build();
    }

    private static String securityPattern(String basePath) {
        String normalized = withoutTrailingSlash(basePath);
        return normalized + "/**";
    }

    private static String childSecurityPattern(String basePath, String childPath) {
        return withoutTrailingSlash(basePath) + "/" + childPath + "/**";
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

    private static final class CsrfCookieFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
