package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * Framework-neutral seam behind the HTTP Clients panel: it reports which declarative HTTP clients the host
 * application registered, without instantiating any of them.
 *
 * <p>The Spring Boot adapter implements this over {@code @HttpExchange} HTTP interface registrations,
 * Spring Cloud OpenFeign registrations, and {@code RestClient.Builder}/{@code WebClient.Builder} beans,
 * reading bean <em>definitions</em> so a lazy client proxy is never created and an application-owned
 * builder is never replaced or decorated. The Quarkus adapter implements it over MicroProfile
 * {@code @RegisterRestClient} metadata captured at build time, so the optional REST Client API is never
 * linked in an application without the extension.</p>
 *
 * <p>Implementations must never contact a target, resolve DNS, or read credentials, private keys or trust
 * material. Values the framework does not expose safely are reported as unavailable rather than guessed.</p>
 */
public interface HttpClientProvider {

    /**
     * Whether this application has at least one discoverable declarative HTTP client. {@code false} makes
     * the panel report a framework-correct empty state built from {@link #unavailableReason()}.
     */
    boolean available();

    /** The framework-correct setup hint shown when {@link #available()} is {@code false}. */
    String unavailableReason();

    /**
     * The discovered clients in adapter order; the engine applies BootUI's stable ordering, identity and
     * sanitization on top. Returns an empty list when {@link #available()} is {@code false}.
     */
    List<DiscoveredHttpClient> clients();
}
