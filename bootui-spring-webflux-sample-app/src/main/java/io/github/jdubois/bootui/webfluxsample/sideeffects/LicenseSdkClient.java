package io.github.jdubois.bootui.webfluxsample.sideeffects;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * An SDK-style client that talks to its service over its own socket, as many vendor SDKs do, so no BootUI panel sees the
 * call ({@code docs/PLAN-v2.md} §5.16, M5-5b): with the BootUI agent, Side Effects shows its connect as a
 * {@code localhost:<port>} row from {@link #check(int)}, <b>not captured by any panel</b>. It asks the application's own
 * runtime-version endpoint, so the seed needs no other service. The request it sends never reaches BootUI.
 */
@org.springframework.stereotype.Component
public class LicenseSdkClient {

    /** The status line the service answered, or why it could not. */
    public String check(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 5_000);
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET /api/side-effects/runtime-version HTTP/1.1\r\nHost: localhost:" + port
                            + "\r\nX-License-Key: never-shown-by-bootui\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String status = in.readLine();
            return status == null ? "no answer" : status;
        } catch (IOException ex) {
            return "license check failed: " + ex.getMessage();
        }
    }
}
