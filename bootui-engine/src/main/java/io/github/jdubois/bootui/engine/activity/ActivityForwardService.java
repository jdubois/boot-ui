package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivityForwardBatchRequest;
import io.github.jdubois.bootui.core.dto.ActivityForwardEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityForwardResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Framework-neutral orchestration behind the Live Activity forwarding-receiver endpoint (Spring's
 * {@code ActivityForwardingController}, and, if/when it exists, a Quarkus equivalent): validates an
 * incoming {@link ActivityForwardBatchRequest} (optional shared-secret check, hard batch-size ceiling,
 * per-entry shape) and, when valid, appends it to the receiver's own local {@link ActivityStore} —
 * mirroring {@code ActivitySwitchService}'s "neutral orchestration behind a thin per-adapter controller"
 * shape, so both adapters render identical outcomes for identical inputs.
 *
 * <p>This class deliberately does <em>not</em> gate on any "am I configured to forward?" setting: a
 * receiving instance accepts forwarded batches unconditionally (subject to the checks below), regardless
 * of whether that same instance is itself also forwarding elsewhere. The two are orthogonal — see {@code
 * ActivityForwardingSettings}'s Javadoc.
 */
public final class ActivityForwardService {

    /**
     * HTTP header carrying the optional shared-secret token. Both {@link HttpActivityStore} (sender) and
     * this class (receiver) reference this single constant so the header name can never drift between
     * the two sides.
     */
    public static final String FORWARD_TOKEN_HEADER = "X-BootUI-Forward-Token";

    /** Sub-path (relative to the {@code /bootui/api/activity} panel prefix) the receiving endpoint serves. */
    public static final String FORWARD_RELATIVE_PATH = "/forward";

    /**
     * Full path, from the BootUI API root, the receiving endpoint is served at. {@link
     * HttpActivityStore} appends this to the configured peer base URL; every adapter's receiving
     * controller/resource must serve exactly this path so the two sides can never drift apart.
     */
    public static final String FORWARD_PATH = "/bootui/api/activity" + FORWARD_RELATIVE_PATH;

    /**
     * Hard ceiling on entries accepted in a single batch, independent of any configuration: a
     * misconfigured or malicious peer sending an enormous POST body must not be able to exhaust the
     * receiver's memory decoding and appending it, regardless of how either side's own {@code
     * bufferMaxEntries} happens to be configured.
     */
    static final int MAX_BATCH_SIZE = 5000;

    private ActivityForwardService() {}

    /**
     * Validates and applies one incoming forwarded batch, returning a neutral response the calling
     * adapter renders directly as an HTTP status + JSON body.
     *
     * <p>Every rejection path (unauthorized, invalid shape, or a downstream {@link
     * ActivityStore#appendBatch} failure) is reported with {@code accepted=0} and never partially
     * applies a batch: validation runs to completion for the whole batch before anything is appended, so
     * a single malformed entry rejects the entire POST rather than silently dropping just that one.
     *
     * @param store the receiver's own local store (typically the adapter's {@code
     *     SwitchableActivityStore}) to append accepted entries to
     * @param configuredSecret the receiver's own configured shared secret ({@code null}/blank to accept
     *     any request, matching the zero-config default described on {@code
     *     ActivityForwardingSettings#sharedSecret()})
     * @param providedToken the token value the caller attached via {@link #FORWARD_TOKEN_HEADER}, or
     *     {@code null} if none was sent
     * @param request the parsed request body, or {@code null} if the adapter received an empty/absent
     *     body
     */
    public static ActivityForwardResponse receive(
            ActivityStore store, String configuredSecret, String providedToken, ActivityForwardBatchRequest request) {
        if (!secretMatches(configuredSecret, providedToken)) {
            return new ActivityForwardResponse(
                    401, new ActivityForwardResult("unauthorized", "Missing or incorrect forwarding token", 0));
        }

        List<ActivityForwardEntryDto> entries = request == null ? null : request.entries();
        if (entries == null || entries.isEmpty()) {
            return new ActivityForwardResponse(
                    400, new ActivityForwardResult("invalid", "Request body must contain at least one entry", 0));
        }
        if (entries.size() > MAX_BATCH_SIZE) {
            return new ActivityForwardResponse(
                    400,
                    new ActivityForwardResult(
                            "invalid", "Batch of " + entries.size() + " exceeds the maximum of " + MAX_BATCH_SIZE, 0));
        }

        List<StoredActivityEntry> toStore = new ArrayList<>(entries.size());
        for (ActivityForwardEntryDto forwarded : entries) {
            if (forwarded == null || isBlank(forwarded.instanceId()) || forwarded.entry() == null) {
                return new ActivityForwardResponse(
                        400,
                        new ActivityForwardResult(
                                "invalid", "Every entry must carry a non-blank instanceId and a non-null entry", 0));
            }
            toStore.add(new StoredActivityEntry(forwarded.instanceId(), forwarded.seq(), forwarded.entry()));
        }

        try {
            store.appendBatch(toStore);
        } catch (RuntimeException appendFailed) {
            return new ActivityForwardResponse(
                    500,
                    new ActivityForwardResult(
                            "failed", "Failed to append forwarded entries: " + appendFailed.getMessage(), 0));
        }
        return new ActivityForwardResponse(
                200, new ActivityForwardResult("accepted", "Appended " + toStore.size() + " entries", toStore.size()));
    }

    /**
     * Constant-time comparison via {@link MessageDigest#isEqual}, so a peer cannot use response-timing
     * differences to guess a configured secret one byte at a time. When no secret is configured, the
     * check is skipped entirely — the zero-config default matching every other BootUI mutating action's
     * trust model, relying instead on the existing {@code LocalhostGuard}/panel-access perimeter.
     */
    private static boolean secretMatches(String configuredSecret, String providedToken) {
        if (isBlank(configuredSecret)) {
            return true;
        }
        if (providedToken == null) {
            return false;
        }
        return MessageDigest.isEqual(
                configuredSecret.getBytes(StandardCharsets.UTF_8), providedToken.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
