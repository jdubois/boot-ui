package io.github.jdubois.bootui.engine.constellation;

import java.time.Duration;

/**
 * Framework-neutral seam for fetching one peer's identity snapshot. Each adapter implements this over
 * its own HTTP client (the Spring adapter uses the JDK {@code HttpClient} + Jackson, mirroring
 * {@code GitHubApiClient}); the engine never touches JSON or a framework HTTP type directly.
 *
 * <p>Implementations must never throw: an unreachable peer, a connection refused, a timeout, or a
 * non-BootUI service on the other end should all be swallowed into a
 * {@link PeerSnapshot#unreachable(String, String)} result so one bad peer never breaks the whole
 * Constellation view (the "never surprise the user" design principle).</p>
 */
public interface PeerClient {

    PeerSnapshot fetch(String peerUrl, Duration timeout);
}
