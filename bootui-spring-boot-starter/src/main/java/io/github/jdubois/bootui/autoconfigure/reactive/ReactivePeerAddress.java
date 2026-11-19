package io.github.jdubois.bootui.autoconfigure.reactive;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.server.reactive.AbstractServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.util.ClassUtils;

/** Reads the connection peer, never Reactor Netty's forwarded connection information. */
public final class ReactivePeerAddress {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ReactivePeerAddress.class);
    private static final boolean NETTY_PRESENT = ClassUtils.isPresent(
            "reactor.netty.http.server.HttpServerRequest", ReactivePeerAddress.class.getClassLoader());
    private static final java.util.concurrent.atomic.AtomicBoolean WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private ReactivePeerAddress() {}

    public static String remoteAddress(ServerHttpRequest request) {
        return remoteAddress(request, false);
    }

    public static String remoteAddress(ServerHttpRequest request, boolean nativeForwarding) {
        ServerHttpRequest current = rawRequest(request);
        Object nativeRequest = nettyRequest(current);
        if (nativeRequest != null) {
            return NettyPeer.address(nativeRequest);
        }
        if (nativeForwarding) {
            if (WARNED.compareAndSet(false, true)) {
                org.slf4j.LoggerFactory.getLogger(ReactivePeerAddress.class)
                        .warn("BootUI cannot establish the raw socket peer with native forwarded-header processing; "
                                + "access is denied. Use Reactor Netty or server.forward-headers-strategy=FRAMEWORK.");
            }
            return null;
        }
        InetSocketAddress remote = current.getRemoteAddress();
        InetAddress address = remote == null ? null : remote.getAddress();
        return address == null ? null : address.getHostAddress();
    }

    public static String hostAuthority(ServerHttpRequest request) {
        ServerHttpRequest current = rawRequest(request);
        Object nativeRequest = nettyRequest(current);
        return nativeRequest == null ? current.getHeaders().getFirst("Host") : NettyPeer.host(nativeRequest);
    }

    private static Object nettyRequest(ServerHttpRequest request) {
        if (NETTY_PRESENT && request instanceof AbstractServerHttpRequest nativeRequest) {
            try {
                Object transport = nativeRequest.getNativeRequest();
                if (NettyPeer.supports(transport)) {
                    return transport;
                }
            } catch (IllegalStateException ex) {
                LOG.debug(
                        "No native transport metadata is available for {}",
                        request.getClass().getName(),
                        ex);
            }
        }
        return null;
    }

    public static ServerHttpRequest rawRequest(ServerHttpRequest request) {
        ServerHttpRequest current = request;
        while (current instanceof ServerHttpRequestDecorator decorator) {
            current = decorator.getDelegate();
        }
        return current;
    }

    private static final class NettyPeer {
        static boolean supports(Object request) {
            return request instanceof reactor.netty.http.server.HttpServerRequest;
        }

        static String address(Object nativeRequest) {
            var request = (reactor.netty.http.server.HttpServerRequest) nativeRequest;
            AtomicReference<SocketAddress> peer = new AtomicReference<>();
            request.withConnection(connection -> peer.set(connection.channel().remoteAddress()));
            if (peer.get() instanceof InetSocketAddress socket && socket.getAddress() != null) {
                return socket.getAddress().getHostAddress();
            }
            return null;
        }

        static String host(Object nativeRequest) {
            return ((reactor.netty.http.server.HttpServerRequest) nativeRequest)
                    .requestHeaders()
                    .get("Host");
        }
    }
}
