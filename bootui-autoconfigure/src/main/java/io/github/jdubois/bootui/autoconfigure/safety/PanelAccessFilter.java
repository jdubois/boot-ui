package io.github.jdubois.bootui.autoconfigure.safety;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.panel.BootUiPanels;
import io.github.jdubois.bootui.autoconfigure.panel.BootUiPanels.Panel;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies per-panel enabled and read-only settings to BootUI API routes.
 */
public class PanelAccessFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final BootUiProperties properties;

    public PanelAccessFilter(BootUiProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return apiRelativePath(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String apiRelativePath = apiRelativePath(request);
        Panel panel = BootUiPanels.byApiPath(apiRelativePath).orElse(null);
        if (panel == null) {
            chain.doFilter(request, response);
            return;
        }

        if (!properties.isPanelEnabled(panel.id())) {
            writeBlockedResponse(response, panel, properties.panelDisabledReason(panel.id()));
            return;
        }

        if (panel.readOnlyCapable()
                && !SAFE_METHODS.contains(request.getMethod())
                && properties.isPanelReadOnly(panel.id())) {
            writeBlockedResponse(response, panel, properties.panelReadOnlyReason(panel.id()));
            return;
        }

        chain.doFilter(request, response);
    }

    private String apiRelativePath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }

        String apiPath = properties.getApiPath();
        if (path.equals(apiPath)) {
            return "/";
        }
        if (!path.startsWith(apiPath + "/")) {
            return null;
        }
        return path.substring(apiPath.length());
    }

    private void writeBlockedResponse(HttpServletResponse response, Panel panel, String reason) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter()
                .write("{\"error\":\"BootUI panel access denied\",\"panel\":\""
                        + escape(panel.id())
                        + "\",\"reason\":\""
                        + escape(reason)
                        + "\"}");
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
