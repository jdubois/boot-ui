package io.github.jdubois.bootui.autoconfigure.crac.fixtures;

import java.net.Socket;

/** Holds an open socket in a field without any managed lifecycle (CRAC-RES-001). */
public class OpenResourceHolder {

    private Socket connection;

    public Socket getConnection() {
        return connection;
    }

    public void setConnection(Socket connection) {
        this.connection = connection;
    }
}
