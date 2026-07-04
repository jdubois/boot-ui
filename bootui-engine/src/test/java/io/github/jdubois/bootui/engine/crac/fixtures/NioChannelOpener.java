package io.github.jdubois.bootui.engine.crac.fixtures;

import java.io.IOException;
import java.nio.channels.SocketChannel;

/**
 * Opens a socket channel via the NIO static {@code open()} factory method (CRAC-NET-001), the
 * idiomatic way NIO channel types are obtained instead of a constructor.
 */
public class NioChannelOpener {

    public SocketChannel open() throws IOException {
        return SocketChannel.open();
    }
}
