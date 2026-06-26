package io.github.jdubois.bootui.sample.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.UrlHandlerFilter;

/**
 * Reproduces GitHub issue #456 ("localhost redirected you too many times") under the
 * {@code redirectloop} profile, using the <em>exact</em> filter the reporter runs.
 *
 * <p>Spring Boot 4 / Spring MVC 7 dropped automatic trailing-slash matching, so applications that
 * still need to accept legacy clients calling {@code /path/} commonly add Spring Framework's
 * {@link UrlHandlerFilter} at {@link Ordered#HIGHEST_PRECEDENCE}. With {@code wrapRequest()} the
 * filter rewrites {@code /bootui/} to {@code /bootui} <em>internally</em> (no client redirect on that
 * leg), so the {@code DispatcherServlet} only ever sees {@code /bootui}.</p>
 *
 * <p>When BootUI answered {@code GET /bootui} with a {@code 302 -> /bootui/}, that combination looped
 * forever:</p>
 *
 * <pre>
 *   GET /bootui   -> (BootUI)                       302 /bootui/
 *   GET /bootui/  -> (UrlHandlerFilter wraps to /bootui, BootUI)  302 /bootui/
 *   GET /bootui/  -> ... forever (every hop targets /bootui/)
 * </pre>
 *
 * <p>This filter is only active under the {@code redirectloop} Spring profile so the default sample
 * app, the "try me" scripts, and the main Playwright suite are unaffected. Run it with:</p>
 *
 * <pre>
 *   ./mvnw -pl bootui-sample-app spring-boot:run -Dspring-boot.run.profiles=dev,redirectloop
 * </pre>
 */
@Component
@Profile("redirectloop")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TrailingSlashHandlerFilter extends OncePerRequestFilter {

    private final UrlHandlerFilter delegate =
            UrlHandlerFilter.trailingSlashHandler("/**").wrapRequest().build();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        delegate.doFilter(request, response, filterChain);
    }
}
