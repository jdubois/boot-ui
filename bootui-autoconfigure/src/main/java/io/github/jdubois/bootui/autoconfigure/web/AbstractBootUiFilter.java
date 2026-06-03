package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public abstract class AbstractBootUiFilter extends OncePerRequestFilter {

    protected final BootUiProperties properties;

    protected AbstractBootUiFilter(BootUiProperties properties) {
        this.properties = properties;
    }

    protected boolean isBootUiApiRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }

        String apiPath = properties.getApiPath();
        return path.equals(apiPath) || path.startsWith(apiPath + "/");
    }

    protected boolean isBootUiRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }

        String basePath = properties.getPath();
        String apiPath = properties.getApiPath();
        return path.equals(basePath)
                || path.startsWith(basePath + "/")
                || path.equals(apiPath)
                || path.startsWith(apiPath + "/");
    }

    protected void writeBlockedResponse(HttpServletResponse response, String reason) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"BootUI access denied\",\"reason\":\"" + escape(reason) + "\"}");
    }

    protected String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
