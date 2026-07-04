package io.github.jdubois.bootui.engine.activity;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP-forwarding {@link ActivityStore} implementation: instead of writing to a local database, every
 * {@link #appendBatch} call is one synchronous, bounded-timeout HTTP POST of the batch to a peer BootUI
 * instance's Live Activity forwarding endpoint ({@link ActivityForwardService#FORWARD_PATH}), which
 * appends the entries into <em>its own</em> durable store (typically a {@code JdbcActivityStore}). This
 * is what lets cross-instance Live Activity forwarding work without a database shared between the two
 * processes: only the receiving instance needs a database, the sender needs only network reachability to
 * it.
 *
 * <p><strong>Deliberately thin, exactly like {@code JdbcActivityStore}.</strong> This class holds no
 * buffer, no retry logic, and no background thread of its own for flushing; it does one blocking call and
 * either succeeds or throws. All of the resilience callers actually need — write-behind buffering, a
 * scheduled flush, retry-with-requeue on failure, a bounded pending queue that degrades gracefully under
 * a sustained outage, and a bounded best-effort final flush on shutdown — comes from wrapping an instance
 * of this class as the {@code durable} argument to the existing, unmodified {@code
 * BufferedActivityStore}, exactly the same relationship {@code JdbcActivityStore} already has to it. See
 * {@code ActivityStoreFactory#create(ActivityPersistenceSettings, ActivityForwardingSettings, java.util.function.Supplier)}.
 * Every {@link IOException}, timeout, interruption, or non-2xx HTTP response is surfaced as an unchecked
 * {@link ActivityStoreException} — precisely what lets {@code BufferedActivityStore}'s existing {@code
 * catch (RuntimeException)} flush handler catch and requeue a failed forward exactly like a failed JDBC
 * write, with no forwarding-specific retry code needed anywhere.
 *
 * <p><strong>{@link #query} is intentionally write-only (always {@link ActivityPage#EMPTY}).</strong> The
 * data this store forwards now lives on the peer's own durable store, browsable through the peer's
 * <em>own</em> Live Activity panel. Proxying reads back over HTTP here would add a second network round
 * trip to every panel page-load/poll on the sender, duplicate the peer's own query/pagination logic, and
 * still not solve the pre-existing limitation that a panel only ever queries its own local {@code
 * instanceId} anyway (there is no cross-instance "switch instance" picker today, even for the existing
 * shared-JDBC persistence mode). Forward-only is the simplest, most honest first version, and matches how
 * {@code ActivityStoreFactory} already composes exactly one queryable backend behind one {@code
 * SwitchableActivityStore}.
 *
 * <p><strong>Thread-safety.</strong> All fields are final and set once at construction; {@link
 * #appendBatch} and {@link #query} hold no shared mutable state, and {@link HttpClient} instances are
 * documented safe for concurrent use by multiple threads. Concurrent callers therefore need no external
 * synchronization.
 *
 * <p><strong>Why this class manages its own {@link java.util.concurrent.ExecutorService} for {@link
 * #close()}.</strong> The {@code HttpClient} API gained explicit {@code close()}/{@code shutdown()}
 * methods only in JDK 21; this codebase targets Java 17 (see the root {@code pom.xml}), so there is no
 * portable way to ask an {@code HttpClient} itself to release its resources. Supplying a custom {@link
 * java.util.concurrent.ExecutorService} at construction and shutting <em>that</em> down in {@link
 * #close()} is the standard workaround, and matches this package's existing convention of every
 * background thread being explicitly named and marked daemon (see {@code BufferedActivityStore}'s
 * {@code "bootui-activity-flush"} thread and {@code ActivityCapturePoller}'s {@code
 * "bootui-activity-capture"} thread) so a slow/unreachable peer can never keep the JVM alive past
 * shutdown.
 */
public final class HttpActivityStore implements ActivityStore {

    private static final String CONTENT_TYPE_JSON = "application/json; charset=UTF-8";

    private final URI forwardUri;
    private final String sharedSecret;
    private final Duration requestTimeout;
    private final java.util.concurrent.ExecutorService httpExecutor;
    private final HttpClient httpClient;

    /**
     * @param peerBaseUrl the receiving instance's base URL, e.g. {@code http://localhost:8080}; must be
     *     a well-formed absolute URL. {@link ActivityForwardService#FORWARD_PATH} is appended by this
     *     constructor to build the actual forwarding endpoint this store posts to
     * @param sharedSecret optional bearer token attached to every request as {@link
     *     ActivityForwardService#FORWARD_TOKEN_HEADER}; {@code null} or blank omits the header entirely
     * @param connectTimeout maximum time to establish the TCP connection to the peer before failing the
     *     attempt; must be a positive duration
     * @param requestTimeout maximum time to wait for the peer's full HTTP response before failing the
     *     attempt; must be a positive duration
     * @throws IllegalArgumentException if {@code peerBaseUrl} is blank or not a well-formed absolute URL,
     *     or if either timeout is missing/non-positive — failing fast at construction, mirroring {@code
     *     JdbcActivityStore}'s own fail-fast table-name validation, rather than failing on the first
     *     background flush
     */
    public HttpActivityStore(
            String peerBaseUrl, String sharedSecret, Duration connectTimeout, Duration requestTimeout) {
        this.forwardUri = resolveForwardUri(peerBaseUrl);
        this.sharedSecret = (sharedSecret == null || sharedSecret.isBlank()) ? null : sharedSecret;
        this.requestTimeout = requirePositive(requestTimeout, "bootui.activity.forwarding.request-timeout");
        Duration effectiveConnectTimeout =
                requirePositive(connectTimeout, "bootui.activity.forwarding.connect-timeout");
        this.httpExecutor = Executors.newFixedThreadPool(2, new ForwardHttpThreadFactory());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(effectiveConnectTimeout)
                .executor(httpExecutor)
                .build();
    }

    @Override
    public void appendBatch(List<StoredActivityEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        String body = ActivityForwardJson.encodeBatch(entries);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(forwardUri)
                .timeout(requestTimeout)
                .header("Content-Type", CONTENT_TYPE_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (sharedSecret != null) {
            requestBuilder.header(ActivityForwardService.FORWARD_TOKEN_HEADER, sharedSecret);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new ActivityStoreException(
                    "Failed to forward " + entries.size() + " activity entries to " + forwardUri, ex);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ActivityStoreException(
                    "Interrupted while forwarding activity entries to " + forwardUri, interrupted);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new ActivityStoreException(
                    "Peer at " + forwardUri + " rejected the forwarded batch with HTTP " + status, null);
        }
    }

    /**
     * Always empty: see the class-level Javadoc for why this store is write-only. The peer's own
     * durable store (and its own Live Activity panel) is the place to read forwarded entries back from.
     */
    @Override
    public ActivityPage query(ActivityQuery query) {
        return ActivityPage.EMPTY;
    }

    /**
     * Shuts down this store's own HTTP executor immediately. Never throws and never blocks: any
     * in-flight request is abandoned rather than awaited, so a peer that is slow or unreachable at
     * shutdown time can never prevent the JVM from exiting. Bounding how long a caller waits for a final
     * flush attempt (if any) before reaching this point is {@code BufferedActivityStore#close()}'s job,
     * not this class's.
     */
    @Override
    public void close() {
        httpExecutor.shutdownNow();
    }

    /**
     * Exposed package-private, for tests only: lets {@code HttpActivityStoreTests} pin the configured
     * connect timeout directly off the built {@link HttpClient}, rather than asserting it indirectly
     * through a real connection attempt that takes as long as the timeout itself to fail (slow and, on a
     * loaded CI machine, potentially flaky).
     */
    Duration connectTimeoutForTesting() {
        return httpClient.connectTimeout().orElse(null);
    }

    private static URI resolveForwardUri(String peerBaseUrl) {
        if (peerBaseUrl == null || peerBaseUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "bootui.activity.forwarding.peer-base-url must be set when forwarding is enabled");
        }
        String trimmed = peerBaseUrl.strip();
        String withoutTrailingSlash = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        try {
            URI uri = new URI(withoutTrailingSlash + ActivityForwardService.FORWARD_PATH);
            if (!uri.isAbsolute()) {
                throw new IllegalArgumentException(
                        "bootui.activity.forwarding.peer-base-url must be an absolute URL, was: " + peerBaseUrl);
            }
            return uri;
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException(
                    "bootui.activity.forwarding.peer-base-url is not a valid URL: " + peerBaseUrl, invalid);
        }
    }

    private static Duration requirePositive(Duration value, String propertyName) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(propertyName + " must be a positive duration, was: " + value);
        }
        return value;
    }

    /** Names every thread this store creates so it is identifiable in a thread dump, and marks it daemon
     * so it can never keep the JVM alive past shutdown on its own. */
    private static final class ForwardHttpThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "bootui-activity-forward-http-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
