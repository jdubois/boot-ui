package io.github.jdubois.bootui.autoconfigure.safety;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Socket provenance shared by guard, authentication and request-id admission. */
public final class ServletPeerAddress {

    public static final String ATTRIBUTE = ServletPeerAddress.class.getName() + ".peer";

    private static final Logger LOG = LoggerFactory.getLogger(ServletPeerAddress.class);
    private final boolean nativeForwarding;
    private final AtomicBoolean warned = new AtomicBoolean();

    public ServletPeerAddress(boolean nativeForwarding) {
        this.nativeForwarding = nativeForwarding;
    }

    public String remoteAddress(HttpServletRequest request) {
        ServletRequest current = rawRequest(request);
        Object captured = current.getAttribute(ATTRIBUTE);
        if (captured instanceof String address) {
            return address;
        }
        if (nativeForwarding) {
            if (warned.compareAndSet(false, true)) {
                LOG.warn(
                        "BootUI cannot establish the raw socket peer with native forwarded-header processing; "
                                + "access is denied. Use a supported raw-peer server binding or server.forward-headers-strategy=FRAMEWORK.");
            }
            return null;
        }
        return current.getRemoteAddr();
    }

    public static HttpServletRequest rawRequest(HttpServletRequest request) {
        ServletRequest current = request;
        while (current instanceof ServletRequestWrapper wrapper) {
            current = wrapper.getRequest();
        }
        return (HttpServletRequest) current;
    }
}
