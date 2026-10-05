package io.github.jdubois.bootui.autoconfigure.safety;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.BootUiPathNormalizer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Prevents Spring Boot's default static-resource handler from exposing the internal classpath mount
 * after BootUI is configured at another public path.
 */
public final class LegacyBootUiPathFilter extends OncePerRequestFilter {

    private final BootUiProperties properties;

    public LegacyBootUiPathFilter(BootUiProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (BootUiPathNormalizer.DEFAULT_PATH.equals(properties.getPath()) || isConfiguredApiRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    private boolean isConfiguredApiRequest(HttpServletRequest request) {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        String apiPath = properties.getApiPath();
        return path.equals(apiPath) || path.startsWith(apiPath + "/");
    }
}
