package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.actuate.web.exchanges.HttpExchange;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.actuate.web.exchanges.Include;
import org.springframework.boot.actuate.web.exchanges.RecordableHttpRequest;
import org.springframework.boot.actuate.web.exchanges.RecordableHttpResponse;
import org.springframework.boot.servlet.actuate.web.exchanges.HttpExchangesFilter;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;
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
 *
 * <p>Recording uses Actuator's public exchange model but guards its observation work separately from the application
 * chain. Malformed header names are ignored, and capture failures cannot replace an application exception or escape
 * after its response has been served.</p>
 */
public class BootUiHttpExchangesFilter extends HttpExchangesFilter {

    private final boolean skipBootUiRequests;
    private final String path;
    private final String apiPath;
    private final HttpExchangeRecording recording;

    public BootUiHttpExchangesFilter(
            HttpExchangeRepository repository,
            Set<Include> includes,
            BootUiProperties properties,
            BootUiSelfDataFilter selfDataFilter) {
        super(repository, includes);
        this.recording = new HttpExchangeRecording(repository, includes);
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

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpExchange.Started started = start(request);
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            if (started != null) {
                recording.finish(
                        started,
                        new ServletResponse(response, completed),
                        request::getUserPrincipal,
                        () -> sessionId(request));
            }
        }
    }

    private static HttpExchange.Started start(HttpServletRequest request) {
        try {
            new URI(request.getRequestURL().toString());
            return HttpExchange.start(new ServletRequest(request));
        } catch (URISyntaxException unrecordable) {
            return null;
        } catch (RuntimeException failure) {
            HttpExchangeRecording.logFailure(failure);
            return null;
        }
    }

    private static String sessionId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : session.getId();
    }

    private record ServletRequest(HttpServletRequest request) implements RecordableHttpRequest {

        @Override
        public URI getUri() {
            String url = request.getRequestURL().toString();
            String query = request.getQueryString();
            if (!StringUtils.hasText(query)) {
                return URI.create(url);
            }
            try {
                return new URI(url + "?" + query);
            } catch (URISyntaxException failure) {
                return URI.create(url + "?" + UriUtils.encodeQuery(query, StandardCharsets.UTF_8));
            }
        }

        @Override
        public String getRemoteAddress() {
            return request.getRemoteAddr();
        }

        @Override
        public String getMethod() {
            return request.getMethod();
        }

        @Override
        public Map<String, List<String>> getHeaders() {
            return HttpExchangeRecording.headers(
                    Collections.list(request.getHeaderNames()), name -> Collections.list(request.getHeaders(name)));
        }
    }

    private record ServletResponse(HttpServletResponse response, boolean completed) implements RecordableHttpResponse {

        @Override
        public int getStatus() {
            return completed ? response.getStatus() : 500;
        }

        @Override
        public Map<String, List<String>> getHeaders() {
            return HttpExchangeRecording.headers(response.getHeaderNames(), response::getHeaders);
        }
    }

    private boolean isBootUiPath(String requestPath) {
        return BootUiMounts.contains(requestPath, path, apiPath);
    }
}
