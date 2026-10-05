package io.github.jdubois.bootui.autoconfigure.crac.fixtures;

import java.io.IOException;
import java.net.ServerSocket;

/** Opens a server socket directly (CRAC-NET-001). */
public class SocketOpener {

    public ServerSocket open() throws IOException {
        return new ServerSocket(8081);
    }
}
