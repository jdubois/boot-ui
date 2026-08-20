package io.github.jdubois.bootui.quarkus.httpclient;

import java.util.List;

/**
 * Build-time-captured holder for the host application's {@code @RegisterRestClient} interfaces, produced by
 * {@link HttpClientsRecorder} and exposed as a synthetic CDI bean by the deployment processor only when a
 * REST Client capability is present (and the launch mode is non-production).
 *
 * <p>{@link QuarkusHttpClientProvider} injects an {@code Instance<QuarkusHttpClients>}: when the bean is
 * absent — the normal case for an application without {@code quarkus-rest-client} — the provider reports the
 * panel unavailable and no MicroProfile REST Client class is ever loaded. The holder exists (rather than
 * injecting a raw {@code List}) so it is an unambiguous synthetic-bean type.</p>
 *
 * @param clients the captured REST client interfaces, in Jandex discovery order (the engine sorts)
 */
public record QuarkusHttpClients(List<RawHttpClient> clients) {

    public QuarkusHttpClients(List<RawHttpClient> clients) {
        this.clients = clients == null ? List.of() : List.copyOf(clients);
    }
}
