package io.github.jdubois.bootui.engine.constellation;

import io.github.jdubois.bootui.core.dto.ConstellationReport;
import io.github.jdubois.bootui.core.dto.PeerNodeDto;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Framework-neutral aggregator for the Constellation panel: fans a configured peer list out to a
 * {@link PeerClient}, in parallel and bounded by a per-peer timeout, and shapes the results into the
 * {@link ConstellationReport} the UI renders.
 *
 * <p>No persistent state, database, or message bus is involved - each call is a fresh, bounded,
 * best-effort read of every configured peer's own {@code /bootui/api/**} contract. A peer that is
 * unreachable, not a BootUI instance, or an older BootUI version missing a field still renders as a
 * node (see {@link PeerSnapshot#unreachable(String, String)}) rather than failing the whole report.</p>
 */
public final class ConstellationService {

    private final List<String> peerUrls;

    private final Duration requestTimeout;

    private final PeerClient peerClient;

    private final ExecutorService executor;

    private ConstellationService(List<String> peerUrls, Duration requestTimeout, PeerClient peerClient) {
        this.peerUrls = List.copyOf(peerUrls);
        this.requestTimeout = requestTimeout;
        this.peerClient = peerClient;
        this.executor = this.peerUrls.isEmpty() ? null : Executors.newFixedThreadPool(
                Math.min(this.peerUrls.size(), 8), daemonThreadFactory());
    }

    public static ConstellationService using(List<String> peerUrls, Duration requestTimeout, PeerClient peerClient) {
        return new ConstellationService(peerUrls, requestTimeout, peerClient);
    }

    /**
     * Fetches every configured peer concurrently (bounded by {@code requestTimeout} each) and returns
     * the assembled report. Returns {@code enabled=false} with no peers when no peer is configured, so
     * the UI can render setup guidance instead of an empty graph.
     */
    public ConstellationReport report() {
        if (peerUrls.isEmpty()) {
            return new ConstellationReport(false, List.of());
        }
        List<CompletableFuture<PeerNodeDto>> futures = peerUrls.stream()
                .map(url -> CompletableFuture.supplyAsync(() -> toDto(safeFetch(url)), executor))
                .toList();
        List<PeerNodeDto> peers =
                futures.stream().map(CompletableFuture::join).toList();
        return new ConstellationReport(true, peers);
    }

    private PeerSnapshot safeFetch(String url) {
        try {
            PeerSnapshot snapshot = peerClient.fetch(url, requestTimeout);
            return snapshot != null ? snapshot : PeerSnapshot.unreachable(url, "No response from peer");
        } catch (RuntimeException e) {
            return PeerSnapshot.unreachable(url, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private PeerNodeDto toDto(PeerSnapshot snapshot) {
        return new PeerNodeDto(
                snapshot.url(),
                snapshot.reachable(),
                snapshot.applicationName(),
                snapshot.platform(),
                snapshot.frameworkVersion(),
                snapshot.javaVersion(),
                snapshot.activeProfiles(),
                snapshot.errorMessage());
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "bootui-constellation-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
