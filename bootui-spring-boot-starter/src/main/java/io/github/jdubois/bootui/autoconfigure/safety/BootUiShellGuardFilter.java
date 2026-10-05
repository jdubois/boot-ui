package io.github.jdubois.bootui.autoconfigure.safety;

import io.github.jdubois.bootui.engine.safety.BootUiInternalMount;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Keeps the packaged BootUI shell dark while BootUI is inactive.
 *
 * <p>When {@code BootUiActivationCondition} resolves to disabled &mdash; a {@code prod}/{@code production}
 * profile, {@code bootui.enabled=OFF}, an invalid {@code bootui.enabled} value, or simply no enabling
 * profile &mdash; no BootUI controller, resource handler, or safety filter is registered at all. The API
 * is therefore already unreachable. The compiled Vue bundle is not: {@code bootui-ui} ships it on the
 * classpath at {@code META-INF/resources/bootui/}, and {@code classpath:/META-INF/resources/} is one of
 * Spring Boot's <strong>default</strong> static-resource locations, wired by
 * {@code WebMvcAutoConfiguration} independently of BootUI. Left alone, {@code GET /bootui/index.html} and
 * every asset below it answer {@code 200} in production with no working API behind them (#856).</p>
 *
 * <p>This filter turns that into a {@code 404}, at parity with the Quarkus adapter's
 * {@code BootUiProdShellGuardFilter}. It is registered by {@code BootUiShellGuardAutoConfiguration} under
 * {@code BootUiInactiveCondition}, the exact negation of the activation condition, so it exists only
 * while the console is off and can never shadow a live BootUI mount.</p>
 *
 * <p>Two details make the match honest rather than approximate:</p>
 * <ul>
 *   <li>It compares the <strong>decoded</strong> application path from {@link UrlPathHelper}, the same
 *       form Spring's handler mapping resolves against. Matching the raw {@code getRequestURI()} would
 *       let {@code /%62ootui/index.html} and {@code /bootui;x=1/index.html} through while they still
 *       resolved to the bundle.</li>
 *   <li>It checks every mount {@code BootUiShellGuardMounts} derives, not only {@code /bootui}: Spring
 *       Boot serves static resources behind {@code spring.mvc.servlet.path} and
 *       {@code spring.mvc.static-path-pattern}, so the same bundle can surface at, say,
 *       {@code /app/bootui/**} or {@code /static/bootui/**}.</li>
 * </ul>
 *
 * <p>The rejection uses {@code sendError} rather than a bare status so the response is the host
 * application's own {@code 404} &mdash; exactly what any unknown URL produces there, which is the point.
 * This also matches the sibling {@link LegacyBootUiPathFilter}.</p>
 */
public final class BootUiShellGuardFilter extends OncePerRequestFilter {

    private final List<String> mounts;

    public BootUiShellGuardFilter(List<String> mounts) {
        this.mounts = List.copyOf(mounts);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (mounts.stream().noneMatch(mount -> BootUiInternalMount.isUnder(path, mount))) {
            filterChain.doFilter(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }
}
