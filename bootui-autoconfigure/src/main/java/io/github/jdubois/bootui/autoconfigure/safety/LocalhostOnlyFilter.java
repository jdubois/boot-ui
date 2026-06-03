package io.github.jdubois.bootui.autoconfigure.safety;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.AbstractBootUiFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rejects any BootUI request that does not originate from a loopback address.
 *
 * <p>The filter is bypassed only when {@code bootui.allow-non-localhost=true}.
 * BootUI is a developer tool, not a production endpoint, so we fail closed by
 * default.</p>
 */
public class LocalhostOnlyFilter extends AbstractBootUiFilter {

    private static final Logger log = LoggerFactory.getLogger(LocalhostOnlyFilter.class);

    public LocalhostOnlyFilter(BootUiProperties properties) {
        super(properties);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isBootUiRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (properties.isAllowNonLocalhost()) {
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
            return address.isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
