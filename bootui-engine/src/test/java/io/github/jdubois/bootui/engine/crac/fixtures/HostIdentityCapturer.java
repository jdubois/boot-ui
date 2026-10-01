package io.github.jdubois.bootui.engine.crac.fixtures;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Resolves the local host name in a static initializer (CRAC-NET-002): the resolved value is frozen into
 * a checkpoint image even though the CRaC JDK clears its own address cache before checkpoint.
 */
public class HostIdentityCapturer {

    static final String HOST_NAME;

    static {
        String hostName;
        try {
            hostName = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            hostName = "unknown";
        }
        HOST_NAME = hostName;
    }
}
