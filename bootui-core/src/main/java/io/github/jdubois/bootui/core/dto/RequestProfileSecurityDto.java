package io.github.jdubois.bootui.core.dto;

/**
 * One security audit event correlated to a profiled request.
 *
 * <p>Spring Security audit events carry no trace id, so they are matched to the request by time
 * window and, when both are known, by principal. The {@code principalMatched} flag records whether
 * the event's principal equalled the request's, distinguishing a strong (principal + time) match
 * from a weaker time-window-only one. The {@code threadMatched} flag records the strongest match:
 * the event was provably emitted on the request's own serving thread, so it belongs to that request
 * exactly even when a concurrent request shares the principal. Values follow BootUI's
 * exposure/masking policy.</p>
 *
 * @param type the audit event type (for example {@code AUTHENTICATION_SUCCESS} or
 *     {@code AUTHORIZATION_FAILURE})
 * @param principal the masked principal the event was recorded for, or {@code null}
 * @param timestamp epoch milliseconds of the event
 * @param principalMatched whether the event principal matched the profiled request's principal
 * @param threadMatched whether the event was emitted on the request's serving thread (exact match)
 */
public record RequestProfileSecurityDto(
        String type, String principal, long timestamp, boolean principalMatched, boolean threadMatched) {}
