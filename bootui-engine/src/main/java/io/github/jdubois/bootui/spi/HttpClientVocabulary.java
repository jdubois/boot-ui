package io.github.jdubois.bootui.spi;

/**
 * The shared vocabulary the HTTP Clients panel uses for client kinds, setting categories, provenance and
 * status values.
 *
 * <p>Adapters emit these tokens through {@link DiscoveredHttpClient} and
 * {@link DiscoveredHttpClientSetting}; the engine normalizes them into the stable JSON contract. Keeping
 * the tokens in one framework-neutral place is what lets Spring servlet, Spring WebFlux and Quarkus render
 * the same UI for equivalent clients.</p>
 */
public final class HttpClientVocabulary {

    /** A Spring HTTP interface client registered from an {@code @HttpExchange} interface. */
    public static final String KIND_HTTP_INTERFACE = "HTTP_INTERFACE";
    /** A Spring Cloud OpenFeign client. */
    public static final String KIND_OPEN_FEIGN = "OPEN_FEIGN";
    /** A MicroProfile {@code @RegisterRestClient} interface (Quarkus REST Client). */
    public static final String KIND_MICROPROFILE_REST_CLIENT = "MICROPROFILE_REST_CLIENT";
    /** A framework-managed {@code RestClient.Builder} bean. */
    public static final String KIND_REST_CLIENT_BUILDER = "REST_CLIENT_BUILDER";
    /** A framework-managed {@code WebClient.Builder} bean. */
    public static final String KIND_WEB_CLIENT_BUILDER = "WEB_CLIENT_BUILDER";

    public static final String CATEGORY_TIMEOUT = "TIMEOUT";
    public static final String CATEGORY_CONNECTION_POOL = "CONNECTION_POOL";
    public static final String CATEGORY_RETRY = "RETRY";
    public static final String CATEGORY_REDIRECT = "REDIRECT";
    public static final String CATEGORY_PROXY = "PROXY";
    public static final String CATEGORY_TLS = "TLS";
    public static final String CATEGORY_TRANSPORT = "TRANSPORT";

    /** The value is a client-specific override. */
    public static final String PROVENANCE_CLIENT = "CLIENT";
    /** The value comes from the client's own declaration annotation. */
    public static final String PROVENANCE_ANNOTATION = "ANNOTATION";
    /** The value comes from an application-wide default shared by every client of this kind. */
    public static final String PROVENANCE_APPLICATION = "APPLICATION";
    /** The value is fixed by the framework and cannot be overridden per client. */
    public static final String PROVENANCE_FRAMEWORK = "FRAMEWORK";
    /** The value is not exposed safely; it is reported as unknown rather than inferred. */
    public static final String PROVENANCE_UNAVAILABLE = "UNAVAILABLE";

    /** The base URL resolved to a usable absolute URL. */
    public static final String BASE_URL_RESOLVED = "RESOLVED";
    /** A base URL is declared but could not be resolved (for example an unresolved placeholder). */
    public static final String BASE_URL_UNRESOLVED = "UNRESOLVED";
    /** No base URL is declared, which is normal for builder beans. */
    public static final String BASE_URL_NOT_DECLARED = "NOT_DECLARED";

    /** Retained REST Client calls were safely attributed to this client. */
    public static final String OBSERVED_LINKED = "LINKED";
    /** This client is attributable but no retained call matched it. */
    public static final String OBSERVED_NO_CALLS = "NO_CALLS";
    /** This client cannot be attributed safely (no resolved host, or a host shared with another client). */
    public static final String OBSERVED_NOT_ATTRIBUTABLE = "NOT_ATTRIBUTABLE";
    /** REST Client trace evidence is not available on this runtime or is disabled. */
    public static final String OBSERVED_UNAVAILABLE = "UNAVAILABLE";

    private HttpClientVocabulary() {}
}
