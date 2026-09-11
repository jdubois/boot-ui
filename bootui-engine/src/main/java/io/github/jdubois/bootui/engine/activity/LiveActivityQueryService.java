package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityPageInfo;
import io.github.jdubois.bootui.core.dto.ActivityPersistenceOptionDto;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The narrow shared read seam. The adapter's existing, richer live merge remains the source of truth;
 * this class owns only live/persistent selection and bounded selected-evidence reads. No capture loop.
 */
public final class LiveActivityQueryService {
    public static final int DETAIL_LIMIT = 2000;

    @FunctionalInterface
    public interface LiveQuery {
        LiveActivityReport report(String type, String severity, long since, int limit);
    }

    public record Selection(ActivityEntryDto event, List<ActivityEntryDto> entries, boolean partial) {}

    private final LiveQuery liveQuery;
    private final Supplier<List<ActivityEntryDto>> retainedEntries;
    private final SwitchableActivityStore store;
    private final ActivityPersistenceSettings settings;
    private final BooleanSupplier dataSourceAvailable;
    private final Predicate<ActivityEntryDto> sourceEnabled;

    public LiveActivityQueryService(
            LiveQuery liveQuery,
            SwitchableActivityStore store,
            ActivityPersistenceSettings settings,
            BooleanSupplier dataSourceAvailable,
            Predicate<ActivityEntryDto> sourceEnabled) {
        this(
                liveQuery,
                () -> liveQuery.report(null, null, 0, DETAIL_LIMIT).entries(),
                store,
                settings,
                dataSourceAvailable,
                sourceEnabled);
    }

    public LiveActivityQueryService(
            LiveQuery liveQuery,
            Supplier<List<ActivityEntryDto>> retainedEntries,
            SwitchableActivityStore store,
            ActivityPersistenceSettings settings,
            BooleanSupplier dataSourceAvailable,
            Predicate<ActivityEntryDto> sourceEnabled) {
        this.liveQuery = liveQuery;
        this.retainedEntries = retainedEntries;
        this.store = store;
        this.settings = settings;
        this.dataSourceAvailable = dataSourceAvailable;
        this.sourceEnabled = sourceEnabled;
    }

    public LiveActivityReport report(
            String type, String severity, long since, int limit, String q, Long until, String cursor, int pageSize) {
        LiveActivityReport live = liveQuery.report(type, severity, since, limit);
        ActivityPersistenceOptionDto persistence = new ActivityPersistenceOptionDto(
                store.persistent(), dataSourceAvailable.getAsBoolean(), settings.tableName());
        ActivityPage page = store.persistent()
                ? store.query(new ActivityQuery(
                        settings.instanceId(),
                        type,
                        severity,
                        q,
                        since > 0 ? since : null,
                        until,
                        cursor,
                        Math.min(2000, pageSize)))
                : null;
        return new LiveActivityReport(
                live.available(),
                live.available()
                        ? permitted(page == null ? live.entries() : page.entryDtos(), live.sources())
                        : List.of(),
                live.typeCounts(),
                live.kpis(),
                live.sources(),
                live.warnings(),
                page == null ? null : new ActivityPageInfo(true, page.nextCursor(), page.hasMore()),
                persistence);
    }

    public Selection select(String id) {
        return select(id, null);
    }

    public Selection select(String id, Long timestamp) {
        if (id == null || id.isBlank() || id.length() > 512 || (timestamp != null && timestamp <= 0)) {
            return new Selection(null, List.of(), false);
        }
        LiveActivityReport live = liveQuery.report(null, null, 0, 1);
        if (!live.available()) return new Selection(null, List.of(), false);
        List<String> sources = live.sources();
        List<ActivityEntryDto> current = permitted(retainedEntries.get(), sources);
        ActivityEntryDto selected = null;
        ActivityQuery first = ActivityQuery.firstPage(settings.instanceId()).withPageSize(DETAIL_LIMIT);
        boolean partial = false;
        if (store.persistent()) {
            // Resolve from the same mode that supplied the list. Source sequence IDs can restart,
            // and aggregated event IDs can survive changed evidence. Timestamp pins a displayed row.
            ActivityQuery exact = new ActivityQuery(
                            settings.instanceId(),
                            null,
                            null,
                            null,
                            timestamp == null ? null : timestamp - 1,
                            timestamp,
                            null,
                            1)
                    .withEventId(id);
            List<ActivityEntryDto> matches = permitted(store.query(exact).entryDtos(), sources);
            selected = matches.isEmpty() ? null : matches.get(0);
        } else {
            selected = current.stream()
                    .filter(e -> id.equals(e.id()) && (timestamp == null || timestamp == e.timestamp()))
                    .findFirst()
                    .orElse(null);
        }
        if (selected == null) return new Selection(null, List.of(), false);
        LinkedHashMap<Capture, ActivityEntryDto> candidates = new LinkedHashMap<>();
        String parent = selected.parentId() == null ? selected.id() : selected.parentId();
        boolean parentVersionsComplete = true;
        if (store.persistent()) {
            if (selected.correlationId() != null) {
                ActivityPage page = store.query(first.withCorrelationId(selected.correlationId()));
                permitted(page.entryDtos(), sources).forEach(entry -> candidates.putIfAbsent(Capture.of(entry), entry));
                partial |= page.hasMore();
            }
            ActivityPage children = store.query(first.withParentId(parent));
            permitted(children.entryDtos(), sources).forEach(entry -> candidates.putIfAbsent(Capture.of(entry), entry));
            partial |= children.hasMore();
            ActivityPage versions = store.query(first.withEventId(parent));
            permitted(versions.entryDtos(), sources).forEach(entry -> candidates.putIfAbsent(Capture.of(entry), entry));
            parentVersionsComplete = !versions.hasMore();
            partial |= versions.hasMore();
        }
        candidates.put(Capture.of(selected), selected);
        ActivityEntryDto event = selected;
        current.stream()
                .filter(entry -> entry.id().equals(parent)
                        || parent.equals(entry.parentId())
                        || (event.correlationId() != null
                                && event.correlationId().equals(entry.correlationId())))
                .forEach(entry -> candidates.putIfAbsent(Capture.of(entry), entry));
        List<ActivityEntryDto> versions = candidates.values().stream()
                .filter(entry -> parent.equals(entry.id()))
                .toList();
        ActivityEntryDto anchor =
                event.parentId() == null ? event : parentVersionsComplete ? uniqueParent(event, versions) : null;
        if (event.parentId() != null && anchor == null) partial = true;
        LinkedHashMap<String, ActivityEntryDto> relatedById = new LinkedHashMap<>();
        Set<String> ambiguousIds = new HashSet<>();
        for (ActivityEntryDto candidate : candidates.values()) {
            if (Capture.of(candidate).equals(Capture.of(event))) continue;
            boolean family = parent.equals(candidate.id()) || parent.equals(candidate.parentId());
            boolean belongs = family
                    ? parentVersionsComplete
                            && anchor != null
                            && (parent.equals(candidate.id())
                                    ? Capture.of(candidate).equals(Capture.of(anchor))
                                    : anchor.equals(uniqueParent(candidate, versions)))
                    : event.correlationId() != null && event.correlationId().equals(candidate.correlationId());
            if (!belongs || candidate.id().equals(event.id())) {
                partial |= family || belongs;
                continue;
            }
            ActivityEntryDto previous = relatedById.putIfAbsent(candidate.id(), candidate);
            if (previous != null && !Capture.of(previous).equals(Capture.of(candidate))) {
                ambiguousIds.add(candidate.id());
            }
        }
        ambiguousIds.forEach(relatedById::remove);
        partial |= !ambiguousIds.isEmpty();
        relatedById.put(event.id(), event);
        List<ActivityEntryDto> related = relatedById.values().stream()
                .sorted(Comparator.comparingLong(ActivityEntryDto::timestamp).thenComparing(ActivityEntryDto::id))
                .limit(DETAIL_LIMIT)
                .toList();
        return new Selection(event, related, partial || related.size() == DETAIL_LIMIT);
    }

    private record Capture(String id, long timestamp) {
        static Capture of(ActivityEntryDto entry) {
            return new Capture(entry.id(), entry.timestamp());
        }
    }

    private static ActivityEntryDto uniqueParent(ActivityEntryDto child, List<ActivityEntryDto> versions) {
        ActivityEntryDto found = null;
        for (ActivityEntryDto parent : versions) {
            if (!possibleParent(child, parent)) continue;
            if (found != null) return null;
            found = parent;
        }
        return found == null || found.durationMs() == null || found.durationMs() < 0 ? null : found;
    }

    private static boolean possibleParent(ActivityEntryDto child, ActivityEntryDto parent) {
        if (!parent.id().equals(child.parentId())
                || (!"REQUEST".equals(parent.type()) && !"SCHEDULED".equals(parent.type()))
                || (child.correlationId() != null
                        && parent.correlationId() != null
                        && !child.correlationId().equals(parent.correlationId()))
                || ("SCHEDULED".equals(parent.type())
                        && child.thread() != null
                        && parent.thread() != null
                        && !child.thread().equals(parent.thread()))
                || ("EXCEPTION".equals(child.type())
                        && ((child.method() != null
                                        && parent.method() != null
                                        && !child.method().equals(parent.method()))
                                || (child.path() != null
                                        && parent.path() != null
                                        && !child.path().equals(parent.path()))))) {
            return false;
        }
        // parentId is not versioned: sequence IDs can restart and exception groups can change.
        // Validate an existing canonical link, never manufacture one from temporal proximity.
        Long duration = parent.durationMs();
        double offset = (double) child.timestamp() - parent.timestamp();
        return offset >= -50 && (duration == null || duration < 0 || offset <= (double) duration + 50);
    }

    private List<ActivityEntryDto> permitted(List<ActivityEntryDto> entries, List<String> sources) {
        return entries.stream()
                .filter(Objects::nonNull)
                .filter(sourceEnabled)
                .filter(entry -> ActivitySourcePolicy.available(entry, sources))
                .toList();
    }
}
