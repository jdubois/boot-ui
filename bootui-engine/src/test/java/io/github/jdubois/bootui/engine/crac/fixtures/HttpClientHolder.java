package io.github.jdubois.bootui.engine.crac.fixtures;

import java.net.http.HttpClient;

/**
 * Holds a long-lived JDK {@link HttpClient} in a field without any managed lifecycle
 * (CRAC-POOL-002). Built via a static factory rather than a bare constructor, so it is easy to miss
 * next to the raw socket/file checks.
 */
public class HttpClientHolder {

    private final HttpClient client = HttpClient.newHttpClient();

    public HttpClient getClient() {
        return client;
    }
}
