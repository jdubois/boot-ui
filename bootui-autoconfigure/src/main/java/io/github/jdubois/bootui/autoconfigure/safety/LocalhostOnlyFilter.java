package io.github.jdubois.bootui.autoconfigure.safety;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects any BootUI request that does not originate from a loopback address.
 *
 * <p>The filter is bypassed only when {@code bootui.allow-non-localhost=true}.
 * BootUI is a developer tool, not a production endpoint, so we fail closed by
 * default.</p>
 */
public class LocalhostOnlyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LocalhostOnlyFilter.class);

    private final BootUiProperties properties;

    public LocalhostOnlyFilter(BootUiProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String basePath = properties.getPath();
        String apiPath = properties.getApiPath();
        return !(path.startsWith(basePath) || path.startsWith(apiPath));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (properties.isAllowNonLocalhost() || !properties.isLocalhostOnly()) {
            chain.doFilter(request, response);
            return;
        }

        String remote = request.getRemoteAddr();
        if (isLoopback(remote)) {
            chain.doFilter(request, response);
            return;
        }

        log.warn("BootUI rejected non-loopback request from {} to {}", remote, request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter()
                .write("{\"error\":\"BootUI is restricted to loopback requests. "
                        + "Set bootui.allow-non-localhost=true to override.\"}");
    }

    private boolean isLoopback(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(remoteAddr);
            return address.isLoopbackAddress() || address.isAnyLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
