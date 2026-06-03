package io.github.jdubois.bootui.autoconfigure.safety;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.panel.BootUiPanels;
import io.github.jdubois.bootui.autoconfigure.panel.BootUiPanels.Panel;
import io.github.jdubois.bootui.autoconfigure.web.AbstractBootUiFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;

/**
 * Applies per-panel enabled and read-only settings to BootUI API routes.
 */
public class PanelAccessFilter extends AbstractBootUiFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    public PanelAccessFilter(BootUiProperties properties) {
        super(properties);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isBootUiApiRequest(request);
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
            writeBlockedResponse(
                    response, "BootUI panel access denied", panel.id(), properties.panelDisabledReason(panel.id()));
            return;
        }

        if (panel.actionCapable()
                && !SAFE_METHODS.contains(request.getMethod())
                && properties.isPanelReadOnly(panel.id())) {
            writeBlockedResponse(
                    response, "BootUI panel access denied", panel.id(), properties.panelReadOnlyReason(panel.id()));
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

    protected void writeBlockedResponse(HttpServletResponse response, String error, String panel, String reason)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter()
                .write("{\"error\":\"" + escape(error) + "\",\"panel\":\"" + escape(panel) + "\",\"reason\":\""
                        + escape(reason) + "\"}");
    }
}
