package io.github.jdubois.bootui.engine.restclienttrace;

import java.net.URI;

/**
 * The host of an outbound call as Live Activity names it ({@code docs/PLAN-v2.md} §5.1): with the port when the
 * captured URI states one, so two local services on different ports stay distinct.
 */
public final class RestClientAuthority {

    private RestClientAuthority() {}

    /**
     * {@code host:port} when {@code uri} states an explicit port, else {@code host}, else an empty string. Never
     * throws; an unparsable URI falls back to the host.
     *
     * @param host the captured host
     * @param uri the captured, already masked URI
     */
    public static String of(String host, String uri) {
        String name = host == null ? "" : host;
        if (uri == null || name.isEmpty()) {
            return name;
        }
        try {
            int port = URI.create(uri).getPort();
            return port < 0 ? name : name + ":" + port;
        } catch (IllegalArgumentException ex) {
            return name;
        }
    }
}
