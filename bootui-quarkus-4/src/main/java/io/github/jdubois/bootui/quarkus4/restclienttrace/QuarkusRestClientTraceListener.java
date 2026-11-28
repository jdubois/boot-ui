package io.github.jdubois.bootui.quarkus4.restclienttrace;

import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.quarkus4.nativeconsole.BootUiNativeActivation;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.spi.RestClientListener;
import org.jboss.logging.Logger;

/**
 * MicroProfile {@link RestClientListener} SPI implementation that registers
 * {@link QuarkusRestClientTraceFilter} on every {@code @RegisterRestClient} proxy built by Quarkus REST Client
 * Reactive. This class has <em>no CDI scope</em> — it is a plain class that the REST Client Reactive
 * extension discovers via the {@code META-INF/services} mechanism. The deployment processor emits that
 * service entry only when the REST Client Reactive capability is present and excludes this optional-API
 * importer from Arc otherwise.
 *
 * <p>Quarkus invokes the listener lazily while building a proxy, after Arc has started. The recorder lookup
 * and filter registration are best-effort so BootUI instrumentation never disrupts the application's own
 * REST client construction.</p>
 */
public final class QuarkusRestClientTraceListener implements RestClientListener {

    private static final Logger LOG = Logger.getLogger(QuarkusRestClientTraceListener.class);
    private static final String CLIENT_TYPE = "Quarkus REST Client Reactive";

    @Override
    public void onNewClient(Class<?> serviceInterface, RestClientBuilder builder) {
        try {
            java.util.function.Supplier<RestClientTraceRecorder> recorder = new java.util.function.Supplier<>() {
                private RestClientTraceRecorder resolved;

                @Override
                public synchronized RestClientTraceRecorder get() {
                    if (resolved == null) {
                        ArcContainer container = Arc.container();
                        if (container == null || !container.isRunning()) {
                            throw new IllegalStateException("Arc is not running");
                        }
                        InstanceHandle<RestClientTraceRecorder> handle =
                                container.instance(RestClientTraceRecorder.class);
                        if (!handle.isAvailable()) {
                            throw new IllegalStateException("The REST Client recorder is unavailable");
                        }
                        RestClientTraceRecorder capture = handle.get();
                        capture.registerClientCustomization(CLIENT_TYPE);
                        resolved = capture;
                    }
                    return resolved;
                }
            };
            if (!BootUiNativeActivation.isNativeInactive()) {
                recorder.get();
            }
            if (!builder.getConfiguration().isRegistered(QuarkusRestClientTraceFilter.class)) {
                // Request filters run in ascending priority and response filters in descending priority.
                // MAX_VALUE therefore brackets the transport after application request filters and before
                // application response filters.
                // A client can be built before native activation freezes. Register only an inert callback;
                // resolving its recorder and recording customization wait for the first admitted request.
                builder.register(new QuarkusRestClientTraceFilter(recorder), Integer.MAX_VALUE);
            }
        } catch (RuntimeException failure) {
            LOG.warnf(
                    "BootUI could not instrument a Quarkus REST Client (%s)",
                    failure.getClass().getSimpleName());
        }
    }
}
