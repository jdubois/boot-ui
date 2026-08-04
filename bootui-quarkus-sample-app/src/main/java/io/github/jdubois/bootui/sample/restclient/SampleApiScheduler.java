package io.github.jdubois.bootui.sample.restclient;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * Periodic scheduler that calls {@link SampleApiClient} every 45 seconds, generating entries in the
 * REST Client panel. The client is instrumented by BootUI's {@code QuarkusRestClientTraceListener} SPI
 * hook, which registers a filter that captures the call metadata into the shared
 * {@code RestClientTraceRecorder}. The call target is the sample app's own loopback address (see
 * {@code quarkus.rest-client.sample-api-client.url} in {@code application.properties}), so no external
 * network access is required.
 */
@ApplicationScoped
public class SampleApiScheduler {

    private static final Logger LOG = Logger.getLogger(SampleApiScheduler.class);

    @RestClient
    SampleApiClient apiClient;

    @Scheduled(every = "45s", delayed = "5s")
    void fetchProducts() {
        try {
            apiClient.listProducts(5);
            LOG.debug("REST Client Reactive sample call succeeded");
        } catch (Exception e) {
            // Tolerate failures during startup (server not fully bound yet) without crashing the scheduler.
            LOG.debugf("REST Client Reactive sample call failed (will retry): %s", e.getMessage());
        }
    }
}
