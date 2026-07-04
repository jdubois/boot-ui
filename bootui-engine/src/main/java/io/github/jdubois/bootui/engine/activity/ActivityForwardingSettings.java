package io.github.jdubois.bootui.engine.activity;

import java.time.Duration;

/**
 * Static configuration for the Live Activity HTTP-forwarding option, mapped once from {@code
 * bootui.activity.forwarding.*} by each adapter's factory — the same "static settings record vs. live
 * policy interface" convention {@link ActivityPersistenceSettings} documents: none of these values has a
 * runtime-override/UI-toggle path, so a settings record is the right shape here too.
 *
 * <p>This is a deliberately separate, additive record rather than a new {@link
 * ActivityPersistenceSettings.DataSourceMode} value: forwarding has no JDBC/{@code DataSource}
 * involvement at all and an unrelated field set (a peer URL, an optional shared secret, HTTP timeouts),
 * so folding it into {@code DataSourceMode} would force irrelevant fields onto the JDBC path and vice
 * versa. The two settings records compose side by side; {@code ActivityStoreFactory} enforces that at
 * most one of {@link ActivityPersistenceSettings#enabled()} and {@link #enabled()} is {@code true} at a
 * time, failing fast at startup rather than silently prioritizing one.
 *
 * <p>{@code instanceId} and {@code captureInterval} intentionally mirror the same fields on {@link
 * ActivityPersistenceSettings}: whichever backend ends up wired (JDBC persistence or HTTP forwarding),
 * something has to tell the capture poller which instance id to stamp captured entries with and how
 * often to poll the merged local activity feed for new ones — that's a property of "this instance is
 * capturing at all," not of "captured entries land in a JDBC table" specifically. See {@code
 * ActivityCaptureFactory}'s primitive-typed overload, which either settings record's fields can drive.
 *
 * @param enabled whether captured entries are forwarded to a peer BootUI instance over HTTP instead of
 *     (or in the absence of) local durable persistence; {@code false} keeps today's behavior with no
 *     forwarding machinery constructed at all
 * @param peerBaseUrl the receiving instance's base URL, e.g. {@code http://localhost:8080}; the
 *     forwarding endpoint path is appended by the store itself. Required (and validated as a
 *     well-formed absolute URI) when {@code enabled} is {@code true}
 * @param sharedSecret optional bearer token attached to every forwarded batch and checked by the
 *     receiver as defense-in-depth on top of the existing loopback/Host-allow-list guard; {@code null}
 *     or blank disables the check on both ends (the zero-config default, matching every other BootUI
 *     mutating action's trust model)
 * @param connectTimeout maximum time to establish the TCP connection to the peer before failing the
 *     attempt (and letting the buffering decorator retry later)
 * @param requestTimeout maximum time to wait for the peer's HTTP response before failing the attempt
 * @param flushInterval how often buffered entries are sent to the peer
 * @param bufferMaxEntries capacity of the pending-forward queue; the oldest entries are dropped (with a
 *     warning) once exceeded during a sustained peer outage, rather than growing unbounded
 * @param instanceId the multi-tenant partition key this instance stamps captured entries with; carried
 *     through unchanged to the receiver so forwarded rows are correctly attributed to the sender, not
 *     the receiver, in the receiver's own durable store
 * @param captureInterval how often the capture coordinator polls the merged local feed for new entries
 *     to forward
 */
public record ActivityForwardingSettings(
        boolean enabled,
        String peerBaseUrl,
        String sharedSecret,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration flushInterval,
        int bufferMaxEntries,
        String instanceId,
        Duration captureInterval) {}
