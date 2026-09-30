package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.actuate.web.exchanges.Include;
import org.springframework.boot.servlet.actuate.web.exchanges.HttpExchangesFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * BootUI's servlet recording filter: Actuator's {@link HttpExchangesFilter}, except that it does not record BootUI's
 * own requests into BootUI's own repository while {@code bootui.monitoring.exclude-self} is on, so console polling
 * never takes a slot from application traffic.
 *
 * <p>The decision uses the decoded path below the servlet context path, exactly as BootUI's safety filters match
 * {@code bootui.path} and {@code bootui.api-path}, never the query string. The repository only ever sees the absolute
 * request URL, which cannot tell a context path from a mount, so the decision is made here. When the repository is an
 * application's own, every request is recorded as before and BootUI hides its own at read time.</p>
 */
public class BootUiHttpExchangesFilter extends HttpExchangesFilter {

    private final boolean skipBootUiRequests;
    private final String path;
    private final String apiPath;

    public BootUiHttpExchangesFilter(
            HttpExchangeRepository repository,
            Set<Include> includes,
            BootUiProperties properties,
            BootUiSelfDataFilter selfDataFilter) {
        super(repository, includes);
        this.skipBootUiRequests = repository instanceof BootUiHttpExchangeRepository bootUiRepository
                && bootUiRepository.ownsRetention()
                && !selfDataFilter.shouldInclude(true);
        this.path = properties.getPath();
        this.apiPath = properties.getApiPath();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return skipBootUiRequests && isBootUiPath(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    }

    private boolean isBootUiPath(String requestPath) {
        return isSameOrChild(requestPath, path) || isSameOrChild(requestPath, apiPath);
    }

    private static boolean isSameOrChild(String requestPath, String mount) {
        return requestPath != null
                && mount != null
                && (requestPath.equals(mount) || requestPath.startsWith(mount + "/"));
    }
}
