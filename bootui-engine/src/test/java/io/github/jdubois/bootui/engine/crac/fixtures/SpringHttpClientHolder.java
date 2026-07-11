package io.github.jdubois.bootui.engine.crac.fixtures;

import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/** Holds Spring HTTP client facades whose underlying transports need lifecycle review. */
public class SpringHttpClientHolder {

    private final RestClient restClient = RestClient.create();
    private final WebClient webClient = WebClient.create();

    public RestClient restClient() {
        return restClient;
    }

    public WebClient webClient() {
        return webClient;
    }
}
