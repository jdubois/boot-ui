package io.github.jdubois.bootui.spi;

/**
 * Supplies the local HTTP port that the in-process HTTP Probe panel should target.
 *
 * <p>This is the framework-neutral seam behind the HTTP Probe: the engine always probes
 * {@code http://localhost:<port><path>}, but <em>which</em> port is the live local server port is a
 * per-framework detail. The Spring Boot adapter resolves it from {@code local.server.port} (the actual
 * bound port, e.g. under a random-port test) falling back to {@code server.port}; the Quarkus adapter
 * resolves it from {@code quarkus.http.port}. It is read <em>live</em> on every probe (not snapshotted
 * at construction) because the bound port is only known after the server has started.
 */
@FunctionalInterface
public interface ServerPortSupplier {

    /** The local HTTP port to probe right now; defaults to {@code 8080} when nothing is configured. */
    int localServerPort();
}
