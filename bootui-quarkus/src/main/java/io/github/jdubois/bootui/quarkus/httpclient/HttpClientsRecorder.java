package io.github.jdubois.bootui.quarkus.httpclient;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import java.util.List;

/**
 * Quarkus recorder that replays the build-time-captured {@code @RegisterRestClient} metadata into a runtime
 * {@link QuarkusHttpClients} holder.
 *
 * <p>The deployment processor's {@code registerHttpClients} build step scans the application's bean archive
 * for {@code @RegisterRestClient} at build time and calls {@link #create(List)} from a
 * {@code @Record(STATIC_INIT)} step; the returned {@link RuntimeValue} backs a synthetic
 * {@link QuarkusHttpClients} bean. Build-time capture is the only safe way to enumerate these interfaces:
 * the runtime alternative would be to resolve each REST client CDI bean, which instantiates the very clients
 * the HTTP Clients panel promises never to instantiate.</p>
 */
@Recorder
public class HttpClientsRecorder {

    /** Wraps the captured rows in a runtime holder backing the synthetic {@link QuarkusHttpClients} bean. */
    public RuntimeValue<QuarkusHttpClients> create(List<RawHttpClient> clients) {
        return new RuntimeValue<>(new QuarkusHttpClients(clients));
    }
}
