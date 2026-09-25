package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

public abstract class AbstractBootUiFilter extends OncePerRequestFilter {

    protected final BootUiProperties properties;

    protected AbstractBootUiFilter(BootUiProperties properties) {
        this.properties = properties;
    }

    /**
     * Returns the context-relative request path in the same form Spring's handler mapping resolves
     * against: percent-decoded, with matrix parameters removed.
     *
     * <p>This must never be replaced by the raw {@code getRequestURI()}. Every BootUI safety filter
     * decides whether it applies by matching this path against {@code bootui.path} /
     * {@code bootui.api-path}, while the controller behind it is selected by {@code PathPattern} on the
     * decoded path. Matching the raw URI makes the two disagree, so {@code /%62ootui/api/**} or
     * {@code /bootui;x=1/api/**} would disarm the loopback, Host allow-list, cross-site-write, bearer
     * token, and per-panel policy filters while still reaching the controller. The sibling
     * {@code BootUiShellGuardFilter} resolves the path the same way for the same reason.</p>
     */
    protected String pathWithinApplication(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }

    protected boolean isBootUiApiRequest(HttpServletRequest request) {
        String path = pathWithinApplication(request);
        String apiPath = properties.getApiPath();
        return path.equals(apiPath) || path.startsWith(apiPath + "/");
    }

    protected boolean isBootUiRequest(HttpServletRequest request) {
        String path = pathWithinApplication(request);
        String basePath = properties.getPath();
        String apiPath = properties.getApiPath();
        return path.equals(basePath)
                || path.startsWith(basePath + "/")
                || path.equals(apiPath)
                || path.startsWith(apiPath + "/");
    }

    protected String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
