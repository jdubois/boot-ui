package io.github.jdubois.bootui.console.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityKpiDto;
import io.github.jdubois.bootui.core.dto.ActivityPageInfo;
import io.github.jdubois.bootui.core.dto.ActivityPersistenceOptionDto;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.StoredActivityEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the console's {@link LiveActivityReport} from one {@link ActivityPage}: the console's
 * self-contained equivalent of the engine's {@code LiveActivityAssembler}, but computing every field
 * (KPIs included) directly from the queried page's own entries rather than re-merging live in-process
 * signal buffers &mdash; the console has none of those, only whatever has already been forwarded to it.
 *
 * <p>Deliberately not shared with {@code LiveActivityAssembler}: that class also owns cross-source
 * correlation (traces/SQL/exceptions/security against live HTTP exchanges) the console does not need,
 * since every forwarded {@link ActivityEntryDto} already arrived fully-formed and already correlated by
 * its {@code correlationId} on the sending instance.
 */
public final class ConsoleActivityReportAssembler {

    private static final String TYPE_REQUEST = "REQUEST";
    private static final String TYPE_SQL = "SQL";
    private static final String TYPE_EXCEPTION = "EXCEPTION";

    private ConsoleActivityReportAssembler() {}

    /**
     * Assembles the full report for a {@link ReactiveActivityStore#queryAllInstances} result page.
     *
     * @param page the queried, already-paginated cross-instance page
     * @param dataSourceAvailable whether the console's own R2DBC connection factory is available (mirrors
     *     {@link ActivityPersistenceOptionDto#dataSourceAvailable()}; always {@code true} in practice for
     *     the console, which cannot run at all without one, but kept as a parameter for parity/testing)
     * @param tableName the console's activity table name
     */
    public static LiveActivityReport assemble(ActivityPage page, boolean dataSourceAvailable, String tableName) {
        List<StoredActivityEntry> stored = page.entries();
        List<ActivityEntryDto> entries = stored.stream()
                .map(ConsoleActivityReportAssembler::withInstancePrefix)
                .toList();

        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        for (ActivityEntryDto entry : entries) {
            typeCounts.merge(entry.type(), 1, Integer::sum);
        }

        List<String> sources = stored.stream()
                .map(StoredActivityEntry::instanceId)
                .distinct()
                .sorted()
                .toList();

        List<String> warnings = entries.isEmpty()
                ? List.of("No activity has been received yet. Point a BootUI-enabled application's "
                        + "bootui.activity.forwarding.peer-base-url at this console to see data here.")
                : List.of();

        ActivityKpiDto kpis = computeKpis(entries);
        ActivityPageInfo pageInfo = new ActivityPageInfo(true, page.nextCursor(), page.hasMore());
        ActivityPersistenceOptionDto persistenceOption =
                new ActivityPersistenceOptionDto(true, dataSourceAvailable, tableName);

        return new LiveActivityReport(true, entries, typeCounts, kpis, sources, warnings, pageInfo, persistenceOption);
    }

    /**
     * Prefixes {@code summary} with {@code [instanceId]}, the console's one concession to a real gap in
     * the shared DTO contract: {@link ActivityEntryDto} carries no {@code instanceId} field (it was
     * designed for a single-instance panel), so without this the merged cross-instance feed &mdash; the
     * console's entire reason to exist &mdash; would give the browser no way to tell which service
     * produced which row. This mirrors the exact convention {@code RemoteActivityEntryDto}'s consumer
     * ({@code LiveActivity.vue}) already renders drill-down entries with ({@code "[instanceId] type ·
     * severity · summary"}), so it needs no new UI code, just applied here to the main feed too.
     */
    private static ActivityEntryDto withInstancePrefix(StoredActivityEntry stored) {
        ActivityEntryDto entry = stored.entry();
        String summary = "[" + stored.instanceId() + "] " + (entry.summary() == null ? "" : entry.summary());
        return new ActivityEntryDto(
                entry.id(),
                entry.type(),
                entry.timestamp(),
                entry.severity(),
                summary,
                entry.detail(),
                entry.durationMs(),
                entry.correlationId(),
                entry.method(),
                entry.path(),
                entry.status(),
                entry.thread(),
                entry.profileable(),
                entry.parentId(),
                entry.securedPrincipal(),
                entry.sqlNPlusOneSuspected());
    }

    private static ActivityKpiDto computeKpis(List<ActivityEntryDto> entries) {
        int requests = 0;
        int errors = 0;
        List<Long> durations = new ArrayList<>();
        long slowestRequestMs = 0;
        String slowestEndpoint = null;
        Long slowestQueryMs = null;
        int exceptionCount = 0;

        for (ActivityEntryDto entry : entries) {
            switch (entry.type()) {
                case TYPE_REQUEST -> {
                    requests++;
                    if (entry.status() != null && entry.status() >= 400) {
                        errors++;
                    }
                    if (entry.durationMs() != null) {
                        durations.add(entry.durationMs());
                        if (entry.durationMs() > slowestRequestMs) {
                            slowestRequestMs = entry.durationMs();
                            slowestEndpoint = entry.path();
                        }
                    }
                }
                case TYPE_SQL -> {
                    if (entry.durationMs() != null && (slowestQueryMs == null || entry.durationMs() > slowestQueryMs)) {
                        slowestQueryMs = entry.durationMs();
                    }
                }
                case TYPE_EXCEPTION -> exceptionCount++;
                default -> {
                    // SECURITY entries contribute to typeCounts/sources only, no dedicated KPI field.
                }
            }
        }

        return new ActivityKpiDto(
                // requestsPerMinute/sqlPerMinute: not computed from the current page's arbitrary time
                // span (a page can cover anywhere from milliseconds to hours of activity depending on
                // volume, making a rate derived from it misleading) — matching the engine's own
                // LiveActivityAssembler, which leaves these at 0d for the same reason today.
                0d,
                requests == 0 ? 0d : (errors * 100d) / requests,
                percentile(durations, 50),
                percentile(durations, 95),
                slowestEndpoint,
                slowestRequestMs == 0 ? null : slowestRequestMs,
                exceptionCount,
                0d,
                slowestQueryMs,
                // healthStatus/heapUsedBytes/heapMaxBytes: not applicable to an aggregator — the console
                // has no health endpoint or heap of its own that is meaningful to the data being viewed.
                null,
                null,
                null);
    }

    private static Long percentile(List<Long> values, int p) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(p / 100d * sorted.size()) - 1);
        return sorted.get(Math.max(0, index));
    }
}
