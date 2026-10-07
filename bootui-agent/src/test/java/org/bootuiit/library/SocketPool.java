package org.bootuiit.library;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * A library connection pool outside the claimed packages: it connects a socket for the application's request and keeps
 * it past the request, as HikariCP or an HTTP client's pool does, then closes it when evicted.
 */
public final class SocketPool {

    private static final List<Socket> IDLE = new ArrayList<>();

    private SocketPool() {}

    /** Connects a socket for the caller and keeps it pooled. */
    public static synchronized Socket borrow(SocketAddress address) throws IOException {
        Socket socket = new Socket();
        socket.connect(address, 5_000);
        IDLE.add(socket);
        return socket;
    }

    /** Closes every pooled socket, as an eviction does. */
    public static synchronized void evictAll() throws IOException {
        for (Socket socket : IDLE) {
            socket.close();
        }
        IDLE.clear();
    }
}
