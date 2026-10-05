package sideeffectsapp;

import io.github.jdubois.bootui.agent.bridge.SideEffects;
import java.net.InetSocketAddress;

/**
 * An application class outside BootUI's packages that connects and resolves names the way the network sensor's advice
 * on {@code Socket.connect} and {@code InetAddress.getAddressesFromNameService} would report it, for the engine's Side
 * Effects tests: an SDK's own socket, which no panel captures unless a REST client call names its host.
 */
public final class Dialer {

    private Dialer() {}

    /** A blocking connect to {@code host:port} that succeeded. */
    public static void connect(String host, int port) {
        long token = SideEffects.networkStarting(SideEffects.HOOK_SOCKET_CONNECT);
        SideEffects.connected(
                token,
                SideEffects.HOOK_SOCKET_CONNECT,
                null,
                InetSocketAddress.createUnresolved(host, port),
                true,
                null);
    }

    /** A name the JVM resolved, its cache missing it. */
    public static void lookup(String host) {
        long token = SideEffects.networkStarting(SideEffects.HOOK_LOOKUP);
        SideEffects.lookedUp(token, host, new java.net.InetAddress[0], null);
    }
}
