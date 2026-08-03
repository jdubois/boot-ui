package io.github.jdubois.bootui.quarkus.restclienttrace;

import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import jakarta.enterprise.inject.spi.CDI;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.spi.RestClientListener;

/**
 * MicroProfile {@link RestClientListener} SPI implementation that registers
 * {@link QuarkusRestClientTraceFilter} on every {@code @RegisterRestClient} proxy built by Quarkus REST Client
 * Reactive. This class has <em>no CDI scope</em> — it is a plain class that the REST Client Reactive
 * extension discovers via the {@code META-INF/services} mechanism (the entry is added conditionally by
 * {@code BootUiQuarkusProcessor.registerRestClientTrace} when the extension is present, so apps without it
 * never load this class).
 *
 * <p>The {@link RestClientTraceRecorder} is resolved lazily via {@code CDI.current()} at the point each REST
 * client proxy is first built (after CDI bootstrap, so the context is always active). Errors during
 * registration are caught and logged as warnings so BootUI instrumentation never disrupts the application's
 * own REST client construction.</p>
 */
public final class QuarkusRestClientTraceListener implements RestClientListener {

    @Override
    public void onNewClient(Class<?> serviceInterface, RestClientBuilder builder) {
        try {
            RestClientTraceRecorder recorder =
                    CDI.current().select(RestClientTraceRecorder.class).get();
            if (recorder == null || !recorder.isEnabled()) {
                return;
            }
            recorder.registerClientCustomization("REST Client Reactive");
            builder.register(new QuarkusRestClientTraceFilter(recorder));
        } catch (Exception e) {
            // Never disrupt the application's REST client construction.
            java.util.logging.Logger.getLogger(QuarkusRestClientTraceListener.class.getName())
                    .warning("BootUI: failed to register REST Client trace filter for " + serviceInterface.getName()
                            + ": " + e.getMessage());
        }
    }
}
