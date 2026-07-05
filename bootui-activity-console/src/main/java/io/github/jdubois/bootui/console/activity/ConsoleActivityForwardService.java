package io.github.jdubois.bootui.console.activity;

import io.github.jdubois.bootui.core.dto.ActivityForwardBatchRequest;
import io.github.jdubois.bootui.core.dto.ActivityForwardEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityForwardResult;
import io.github.jdubois.bootui.engine.activity.ActivityForwardResponse;
import io.github.jdubois.bootui.engine.activity.StoredActivityEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Reactive counterpart to the engine's {@code ActivityForwardService}, adapted to call a {@link
 * ReactiveActivityStore} instead of the blocking {@code ActivityStore}: validates an incoming {@link
 * ActivityForwardBatchRequest} (optional shared-secret check, hard batch-size ceiling, per-entry shape)
 * and, when valid, appends it to the console's own {@link ReactiveActivityStore}.
 *
 * <p>Duplicated rather than reused directly &mdash; {@code ActivityForwardService} is a {@code static}
 * utility hard-coded against the blocking {@code ActivityStore} interface, and its {@code
 * MAX_BATCH_SIZE} constant is package-private &mdash; but every validation rule and response shape below
 * is kept byte-identical to it (same status codes, same machine-readable {@code status} strings, same
 * messages) so a sender cannot tell whether it is forwarding to a host application or to this console.
 */
public final class ConsoleActivityForwardService {

    /**
     * Mirrors the engine {@code ActivityForwardService.MAX_BATCH_SIZE} (package-private there, so
     * duplicated here): a hard ceiling on entries accepted in a single batch, independent of any
     * configuration, so a misconfigured or malicious peer cannot exhaust the console's memory decoding
     * and appending an enormous POST body.
     */
    static final int MAX_BATCH_SIZE = 5000;

    private final ReactiveActivityStore store;
    private final String configuredSecret;
    private final ConsoleActivityChangeStream changeStream;

    public ConsoleActivityForwardService(
            ReactiveActivityStore store, String configuredSecret, ConsoleActivityChangeStream changeStream) {
        this.store = store;
        this.configuredSecret = configuredSecret;
        this.changeStream = changeStream;
    }

    /**
     * Validates and applies one incoming forwarded batch, returning a neutral response the calling
     * controller renders directly as an HTTP status + JSON body. Never partially applies a batch:
     * validation runs to completion before anything is appended, so a single malformed entry rejects the
     * entire POST rather than silently dropping just that one.
     */
    public Mono<ActivityForwardResponse> receive(String providedToken, ActivityForwardBatchRequest request) {
        if (!secretMatches(configuredSecret, providedToken)) {
            return Mono.just(new ActivityForwardResponse(
                    401, new ActivityForwardResult("unauthorized", "Missing or incorrect forwarding token", 0)));
        }

        List<ActivityForwardEntryDto> entries = request == null ? null : request.entries();
        if (entries == null || entries.isEmpty()) {
            return Mono.just(new ActivityForwardResponse(
                    400, new ActivityForwardResult("invalid", "Request body must contain at least one entry", 0)));
        }
        if (entries.size() > MAX_BATCH_SIZE) {
            return Mono.just(new ActivityForwardResponse(
                    400,
                    new ActivityForwardResult(
                            "invalid", "Batch of " + entries.size() + " exceeds the maximum of " + MAX_BATCH_SIZE, 0)));
        }

        List<StoredActivityEntry> toStore = new ArrayList<>(entries.size());
        for (ActivityForwardEntryDto forwarded : entries) {
            if (forwarded == null || isBlank(forwarded.instanceId()) || forwarded.entry() == null) {
                return Mono.just(new ActivityForwardResponse(
                        400,
                        new ActivityForwardResult(
                                "invalid", "Every entry must carry a non-blank instanceId and a non-null entry", 0)));
            }
            toStore.add(new StoredActivityEntry(forwarded.instanceId(), forwarded.seq(), forwarded.entry()));
        }

        int accepted = toStore.size();
        return store.appendBatch(toStore)
                .doOnSuccess(ignored -> changeStream.signal())
                .thenReturn(new ActivityForwardResponse(
                        200, new ActivityForwardResult("accepted", "Appended " + accepted + " entries", accepted)))
                .onErrorResume(ex -> Mono.just(new ActivityForwardResponse(
                        500,
                        new ActivityForwardResult(
                                "failed", "Failed to append forwarded entries: " + ex.getMessage(), 0))));
    }

    /**
     * Constant-time comparison via {@link MessageDigest#isEqual}, so a peer cannot use response-timing
     * differences to guess a configured secret one byte at a time. Skipped entirely when no secret is
     * configured &mdash; the zero-config default matching every other BootUI mutating action's trust
     * model, relying on the console's own {@code ConsoleSafetyFilter} perimeter instead.
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
