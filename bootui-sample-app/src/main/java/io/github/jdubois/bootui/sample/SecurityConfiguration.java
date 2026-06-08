package io.github.jdubois.bootui.sample;

import static org.springframework.security.config.Customizer.withDefaults;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    @Order(1)
    SecurityFilterChain bootUiSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/bootui/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfTokenRequestHandler())
                        .ignoringRequestMatchers("/bootui/api/otlp/**"))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain adminSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/admin/**", "/api/secure")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole("ADMIN"))
                .httpBasic(withDefaults())
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain applicationSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/chat"))
                .build();
    }

    @Bean
    UserDetailsService users(PasswordEncoder passwordEncoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername("developer")
                        .password(passwordEncoder.encode("developer"))
                        .roles("USER")
                        .build(),
                User.withUsername("admin")
                        .password(passwordEncoder.encode("admin"))
                        .roles("ADMIN")
                        .build());
    }

    @Bean
    @Primary
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Intentionally insecure bean that demonstrates the Security Advisor's only CRITICAL rule,
     * SEC-AUTH-001 (a NoOpPasswordEncoder stores credentials in clear text). The application
     * authenticates with the {@code @Primary} delegating bcrypt encoder above; this bean exists
     * purely so the sample app surfaces a real CRITICAL finding in the Security panel.
     */
    @Bean
    @SuppressWarnings("deprecation")
    PasswordEncoder insecureDemoPasswordEncoder() {
        return NoOpPasswordEncoder.getInstance();
    }

    @Bean
    ApplicationRunner sampleAuditEvents(ObjectProvider<AuditEventRepository> auditEventRepositoryProvider) {
        return args -> {
            AuditEventRepository repository = auditEventRepositoryProvider.getIfAvailable();
            if (repository == null) {
                log.info("Skipping sample Security Logs events because no AuditEventRepository bean is available");
                return;
            }
            repository.add(new AuditEvent(
                    Instant.now().minusSeconds(300),
                    "developer",
                    "AUTHENTICATION_SUCCESS",
                    Map.of("path", "/api/public", "remoteAddress", "127.0.0.1")));
            repository.add(new AuditEvent(
                    Instant.now().minusSeconds(120),
                    "anonymousUser",
                    "AUTHORIZATION_DENIED",
                    Map.of("path", "/api/secure", "sessionId", "sample-session")));
            repository.add(new AuditEvent(
                    Instant.now().minusSeconds(60),
                    "admin",
                    "AUTHENTICATION_SUCCESS",
                    Map.of("path", "/api/secure", "details", "HTTP Basic login accepted")));
        };
    }

    private static CsrfTokenRequestAttributeHandler csrfTokenRequestHandler() {
        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        requestHandler.setCsrfRequestAttributeName(null);
        return requestHandler;
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
