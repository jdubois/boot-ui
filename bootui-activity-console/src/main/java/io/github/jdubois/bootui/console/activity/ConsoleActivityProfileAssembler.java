package io.github.jdubois.bootui.console.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.RemoteActivityEntryDto;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.engine.activity.StoredActivityEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Builds a {@link RequestProfileDto} for the console's per-request drill-down ({@code GET
 * /bootui/api/activity/request/{id}}), the reactive counterpart to the engine's {@code
 * RemoteActivityCorrelator} &mdash; but the console's entire profile <em>is</em> "remote activity"
 * (unlike a host application, which has its own locally-correlated {@code sql}/{@code exceptions}/
 * {@code security}/{@code trace} to show alongside remote signals, the console has none of those: every
 * signal it holds arrived pre-flattened from some sending instance).
 *
 * <p>Every correlated {@link ActivityEntryDto} (across every instance, including the one that produced
 * the anchor entry itself) is surfaced through {@link RequestProfileDto#remoteActivity()} &mdash; the
 * one field already shaped for "an entry plus which instance captured it". This means {@code sql},
 * {@code sqlGroups}, {@code exceptions} and {@code security} are always empty and {@code trace}/{@code
 * timing} always {@code null}: the richer per-type shapes those fields need (raw SQL text, stack
 * traces, span waterfalls) do not survive being flattened into a wire {@link ActivityEntryDto}, so
 * fabricating them would be dishonest. A {@link RequestProfileDto#notes()} entry says so explicitly.
 */
public final class ConsoleActivityProfileAssembler {

    /**
     * Upper bound on correlated entries returned for one request, mirroring the engine {@code
     * RemoteActivityCorrelator.MAX_REMOTE_ENTRIES}: a pathological shared trace id can't balloon a
     * single profile response.
     */
    private static final int MAX_CORRELATED_ENTRIES = 100;

    private static final String NO_DETAIL_NOTE = "SQL, exception and security detail is only available on the "
            + "originating instance's own Live Activity panel; the console shows only what each instance already "
            + "summarized in its own forwarded entries.";

    private ConsoleActivityProfileAssembler() {}

    /**
     * @param store the console's own store to query
     * @param entryId the clicked entry's own {@code id} (the shared Vue UI never requests a profile by
     *     {@code correlationId})
     */
    public static Mono<RequestProfileDto> assemble(ReactiveActivityStore store, String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return Mono.just(RequestProfileDto.unavailable("No activity entry id was provided"));
        }
        return store.findByEntryId(entryId)
                .flatMap(anchor -> buildForAnchor(store, anchor))
                .switchIfEmpty(Mono.just(RequestProfileDto.unavailable("No activity entry found with id " + entryId)));
    }

    private static Mono<RequestProfileDto> buildForAnchor(ReactiveActivityStore store, StoredActivityEntry anchor) {
        HttpExchangeDto request = toHttpExchange(anchor.entry());
        String correlationId = anchor.entry().correlationId();
        if (correlationId == null || correlationId.isBlank()) {
            RemoteActivityEntryDto self =
                    new RemoteActivityEntryDto(anchor.instanceId(), withoutParent(anchor.entry()));
            return Mono.just(new RequestProfileDto(
                    true,
                    null,
                    request,
                    List.of(),
                    List.of(),
                    false,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    List.of(
                            "This entry carries no trace id, so no cross-instance correlation was possible.",
                            NO_DETAIL_NOTE),
                    List.of(self)));
        }
        return store.queryByCorrelationId(correlationId, MAX_CORRELATED_ENTRIES).map(rows -> {
            List<RemoteActivityEntryDto> correlated = new ArrayList<>();
            for (StoredActivityEntry row : rows) {
                correlated.add(new RemoteActivityEntryDto(row.instanceId(), withoutParent(row.entry())));
            }
            correlated.sort(Comparator.comparingLong(remote -> remote.entry().timestamp()));
            return new RequestProfileDto(
                    true,
                    null,
                    request,
                    List.of(),
                    List.of(),
                    false,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    List.of(NO_DETAIL_NOTE),
                    correlated);
        });
    }

    private static HttpExchangeDto toHttpExchange(ActivityEntryDto entry) {
        int status = entry.status() == null ? 0 : entry.status();
        return new HttpExchangeDto(
                entry.id(),
                Instant.ofEpochMilli(entry.timestamp()),
                entry.method(),
                entry.path(),
                null,
                entry.path(),
                status,
                statusFamily(status),
                entry.durationMs(),
                null,
                null,
                entry.securedPrincipal(),
                null,
                entry.correlationId(),
                List.of(),
                List.of());
    }

    private static String statusFamily(int status) {
        if (status <= 0) {
            return "";
        }
        return (status / 100) + "xx";
    }

    private static ActivityEntryDto withoutParent(ActivityEntryDto entry) {
        return new ActivityEntryDto(
                entry.id(),
                entry.type(),
                entry.timestamp(),
                entry.severity(),
                entry.summary(),
                entry.detail(),
                entry.durationMs(),
                entry.correlationId(),
                entry.method(),
                entry.path(),
                entry.status(),
                entry.thread(),
                entry.profileable(),
                null,
                entry.securedPrincipal(),
                entry.sqlNPlusOneSuspected());
    }
}
