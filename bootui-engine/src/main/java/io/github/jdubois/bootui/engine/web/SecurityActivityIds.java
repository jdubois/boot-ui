package io.github.jdubois.bootui.engine.web;

import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import java.util.Objects;

/**
 * Derives a stable Live Activity entry id for a security/audit event.
 *
 * <p>The id is computed from the event's own content (timestamp, principal, type, data, and trace id)
 * rather than its position in a periodically rebuilt list. A position-based id (e.g. {@code "sec-" +
 * index}) is unstable across polls whenever the underlying event source (Spring's {@code
 * AuditEventRepository}, Quarkus's security-event buffer) shifts entries between reads: the same
 * logical event can be assigned a different id on the next poll (causing it to look "new" and be
 * re-captured), or a genuinely new event can coincidentally reuse an id already recorded as seen
 * (causing it to be silently skipped). Both are real correctness risks for anything that dedups Live
 * Activity entries by id, such as {@code ActivityCaptureCoordinator} when persisting to a durable store.
 *
 * <p>Two distinct events sharing an identical timestamp, principal, type, data, and trace id are
 * indistinguishable from the user's perspective anyway, so a hash collision between them is an
 * acceptable, deliberate trade-off — not a correctness bug in practice.
 */
public final class SecurityActivityIds {

    private SecurityActivityIds() {}

    public static String stableId(SecurityLogEventDto event) {
        int hash = Objects.hash(event.timestamp(), event.principal(), event.type(), event.data(), event.traceId());
        return "sec-" + Integer.toHexString(hash);
    }
}
