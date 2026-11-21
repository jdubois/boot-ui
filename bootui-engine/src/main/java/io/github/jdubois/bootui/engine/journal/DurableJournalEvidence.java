package io.github.jdubois.bootui.engine.journal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Bounded reader that merges a run's durable history with the matching hot runtime-journal tail.
 *
 * <p>No adapter or database handle is required by consumers: obtain one snapshot through {@link #read} and pass it
 * to a journal-derived service. Store pages are bounded by the durable query contract and the merged map never holds
 * more than the runtime journal's configured event count or byte budget.</p>
 */
public final class DurableJournalEvidence {

    private final DurableJournalStore store;
    private final int maxReadEvents;
    private final long maxReadBytes;

    public DurableJournalEvidence(DurableJournalStore store) {
        this(store, Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    /** A durable reader with stricter event and byte work bounds than the persisted runtime settings. */
    public DurableJournalEvidence(DurableJournalStore store, int maxReadEvents, long maxReadBytes) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        if (maxReadEvents < 1) {
            throw new IllegalArgumentException("maxReadEvents must be positive");
        }
        if (maxReadBytes < 1) {
            throw new IllegalArgumentException("maxReadBytes must be positive");
        }
        this.maxReadEvents = maxReadEvents;
        this.maxReadBytes = maxReadBytes;
    }

    /** Reads {@code scope}, with no hot in-memory contribution. */
    public JournalEvidenceSnapshot read(DurableJournalScope scope) {
        return read(scope, null);
    }

    /** Reads the requested run and merges its matching hot tail. */
    public JournalEvidenceSnapshot read(RuntimeJournal journal, DurableJournalScope scope) {
        return read(scope, journal);
    }

    /** Alias for callers that name this object as a snapshot reader. */
    public JournalEvidenceSnapshot snapshot(RuntimeJournal journal, DurableJournalScope scope) {
        return read(scope, journal);
    }

    /** Reads the requested run and merges its matching hot tail. */
    public JournalEvidenceSnapshot read(DurableJournalScope scope, RuntimeJournal journal) {
        return read(scope, journal, null);
    }

    JournalEvidenceSnapshot read(
            DurableJournalScope scope, RuntimeJournal journal, DurableJournalCapture.ReadWindow liveWindow) {
        Objects.requireNonNull(scope, "scope must not be null");
        boolean sameRun = journal != null && scope.run().equals(journal.run().id());
        JournalEvidenceSnapshot hot = sameRun ? JournalEvidenceSnapshot.hot(journal) : null;
        JournalStatus hotStatus = hot == null ? null : hot.status();
        if (sameRun
                && liveWindow == null
                && hotStatus.offloadedEvents() == 0
                && hotStatus.evictedByCount() + hotStatus.evictedByBytes() == 0
                && hotStatus.clears() == 0) {
            return hot;
        }

        DurableJournalRunMetadata metadata = store.findRunMetadata(scope);
        JournalStatus base = hotStatus == null ? metadata == null ? null : metadata.journalStatus() : hotStatus;
        if (base == null) {
            throw new DurableJournalUnavailableException(
                    "Durable journal run metadata is required when no matching hot journal is supplied: " + scope);
        }
        RuntimeJournalSettings settings = metadata == null ? null : metadata.journalSettings();
        int eventBound = Math.min(base.maxEvents(), maxReadEvents);
        long byteBound = Math.min(Math.max(0, base.maxBytes() - base.dictionaryBytes()), maxReadBytes);
        int pageSize = Math.min(DurableJournalQuery.MAX_PAGE_SIZE, Math.max(1, eventBound));
        List<JournalEntry> hotEntries = hot == null ? List.of() : hot.entries();
        Set<Long> hotSequences = sameRun
                ? new java.util.HashSet<>(
                        hotEntries.stream().map(JournalEntry::sequence).toList())
                : Set.of();
        long durableThrough = base.lastSequence();
        long durableCount = 0;
        long durableOmitted = 0;
        long overlap = 0;
        BoundedEntries bounded = new BoundedEntries(eventBound, byteBound);
        long clearedThrough = metadata == null ? 0 : metadata.durableClearedThrough();
        if (liveWindow != null) {
            clearedThrough = Math.max(clearedThrough, liveWindow.clearedThrough());
        }
        long firstSequence = clearedThrough;
        if (!sameRun && metadata != null) {
            firstSequence = Math.max(0, metadata.journalStatus().lastSequence() - (long) eventBound);
        } else if (sameRun) {
            firstSequence = Math.max(0, durableThrough - (long) eventBound);
        }
        if (liveWindow != null) {
            firstSequence = Math.max(firstSequence, clearedThrough);
        }
        durableOmitted = Math.max(0, firstSequence - clearedThrough);
        DurableJournalCursor cursor = firstSequence == 0 ? null : new DurableJournalCursor(firstSequence);
        Set<Long> availableSequences = new java.util.HashSet<>(hotSequences);
        boolean completeThroughScope = false;
        do {
            DurableJournalPage page = store.query(
                    new DurableJournalQuery(scope, null, false, null, null, null, null, null, cursor, pageSize));
            for (JournalEntry entry : page.entries()) {
                if (entry.sequence() > durableThrough) {
                    completeThroughScope = true;
                    break;
                }
                availableSequences.add(entry.sequence());
                durableCount++;
                if (hotSequences.contains(entry.sequence())) {
                    overlap++;
                }
                bounded.add(entry);
            }
            if (completeThroughScope || !page.hasMore()) {
                break;
            }
            cursor = page.nextCursor();
            if (cursor == null) {
                throw new DurableJournalUnavailableException(
                        "Durable journal page advertised more entries without a cursor: " + scope);
            }
        } while (true);
        for (JournalEntry entry : hotEntries) {
            bounded.add(entry);
            availableSequences.add(entry.sequence());
        }

        List<JournalEntry> entries = enrich(scope, bounded.newestFirst());
        long missingSequences = 0;
        for (long sequence = firstSequence + 1; sequence <= durableThrough; sequence++) {
            if (!availableSequences.contains(sequence)) {
                missingSequences++;
            }
        }
        long unique = durableCount + hotEntries.size() - overlap;
        long omitted = Math.max(0, durableOmitted + unique - entries.size()) + missingSequences;
        // Memory offload also discards undispatched events, which have no sequence to check against disk.
        boolean durableComplete = (liveWindow == null || !journal.hasUnsequencedLoss())
                && metadata != null
                && omitted == 0
                && base.droppedTotal() == 0
                && metadata.durableDroppedEvents() == 0
                && metadata.durableQuarantinedEvents() == 0;
        JournalStatus status = withEvidence(base, entries, bounded.bytes(), durableComplete);
        boolean currentArchive = sameRun && liveWindow != null;
        boolean recoveredOffload = currentArchive && metadata != null && omitted == 0;
        JournalEvidenceSnapshot.Completeness completeness = completeness(
                scope, journal, sameRun, metadata, settings, durableComplete, currentArchive, recoveredOffload);
        long captureLoss = metadata == null
                ? 0
                : Math.addExact(metadata.durableDroppedEvents(), metadata.durableQuarantinedEvents());
        return new JournalEvidenceSnapshot(entries, status, omitted, captureLoss, completeness);
    }

    private List<JournalEntry> enrich(DurableJournalScope scope, List<JournalEntry> entries) {
        if (entries.isEmpty()) {
            return entries;
        }
        long first = entries.stream().mapToLong(JournalEntry::sequence).min().orElseThrow();
        int detailLimit = (int) Math.min(DurableJournalStore.MAX_DETAILS_PER_QUERY, (long) entries.size() * 2);
        List<DurableJournalDetail> details = store.listDetails(scope, first, detailLimit);
        java.util.Map<Long, DurableJournalDetail> requests = details.stream()
                .filter(detail -> detail.kind() == DurableJournalDetailKind.REQUEST)
                .collect(java.util.stream.Collectors.toMap(DurableJournalDetail::journalSequence, detail -> detail));
        java.util.Map<Long, DurableJournalDetail> exceptions = details.stream()
                .filter(detail -> detail.kind() == DurableJournalDetailKind.EXCEPTION)
                .collect(java.util.stream.Collectors.toMap(DurableJournalDetail::journalSequence, detail -> detail));
        List<JournalEntry> enriched = new ArrayList<>(entries.size());
        for (JournalEntry entry : entries) {
            RuntimeEvent event = entry.event();
            RuntimeEventPayload replacement = event.payload();
            if (replacement instanceof HttpPayload http && requests.containsKey(entry.sequence())) {
                DurableJournalDetail detail = requests.get(entry.sequence());
                replacement = new HttpPayload(
                        firstNonBlank(http.method(), detail.requestMethod()),
                        http.path(),
                        firstNonBlank(http.routeTemplate(), detail.routeTemplate()),
                        firstNonBlank(http.operation(), detail.operation()),
                        http.status(),
                        http.resources(),
                        http.timing(),
                        http.asyncStarted());
            } else if (replacement instanceof ExceptionPayload exception && exceptions.containsKey(entry.sequence())) {
                DurableJournalDetail detail = exceptions.get(entry.sequence());
                LinkedHashSet<String> types = new LinkedHashSet<>(exception.types());
                types.addAll(detail.types());
                types.addAll(detail.causes());
                replacement = new ExceptionPayload(
                        firstNonBlank(exception.groupId(), detail.exceptionGroupId()),
                        firstNonBlank(exception.exceptionClass(), detail.exceptionClass()),
                        firstNonBlank(exception.signature(), detail.signature()),
                        types.stream().limit(ExceptionPayload.MAX_TYPES).toList(),
                        exception.marks(),
                        exception.logged());
            }
            enriched.add(
                    replacement == event.payload()
                            ? entry
                            : new JournalEntry(
                                    entry.sequence(), event.withPayload(replacement), entry.estimatedBytes()));
        }
        return List.copyOf(enriched);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static JournalStatus withEvidence(
            JournalStatus base, List<JournalEntry> entries, long retainedBytes, boolean durableComplete) {
        Long oldest = entries.stream()
                .map(entry -> entry.event().epochMillis())
                .min(Long::compare)
                .orElse(null);
        return new JournalStatus(
                base.enabled(),
                base.instanceId(),
                base.runId(),
                base.lastSequence(),
                entries.size(),
                retainedBytes,
                base.dictionaryEntries(),
                base.dictionaryBytes(),
                base.maxEvents(),
                base.maxBytes(),
                0,
                base.reservedCapacity(),
                durableComplete ? 0 : base.evictedByCount(),
                durableComplete ? 0 : base.evictedByBytes(),
                base.bindingBound(),
                oldest,
                base.queueDepth(),
                base.queueCapacity(),
                base.accepted(),
                base.dropped(),
                base.listenerFailures(),
                base.clears(),
                base.offloadedEvents(),
                base.durableOffloadedThrough());
    }

    private static JournalEvidenceSnapshot.Completeness completeness(
            DurableJournalScope scope,
            RuntimeJournal journal,
            boolean sameRun,
            DurableJournalRunMetadata metadata,
            RuntimeJournalSettings settings,
            boolean durableComplete,
            boolean currentArchive,
            boolean recoveredOffload) {
        Set<String> lostTraces = metadata == null ? Set.of() : Set.copyOf(metadata.lostRequestTraces());
        Set<String> lostThreads = metadata == null ? Set.of() : Set.copyOf(metadata.lostRequestThreads());
        return new JournalEvidenceSnapshot.Completeness() {
            @Override
            public boolean records(JournalSource source) {
                if (sameRun) {
                    return journal.records(source);
                }
                return settings != null && settings.records(source);
            }

            @Override
            public Long lossHorizonMillis() {
                if (durableComplete) {
                    return null;
                }
                if (recoveredOffload) {
                    // Both the hot and saved horizon include memory-only discards; the complete sequence merge
                    // recovered those rows. Keep actual loss, including admissions without a sequence.
                    return journal.lossHorizonMillis(true);
                }
                return maximum(
                        sameRun ? journal.lossHorizonMillis() : null,
                        metadata == null ? null : metadata.lossHorizonMillis());
            }

            @Override
            public Long lostRequestEndMillis() {
                if (durableComplete) {
                    return null;
                }
                if (recoveredOffload) {
                    return journal.lostRequestEndMillis(true);
                }
                return maximum(
                        sameRun ? journal.lostRequestEndMillis() : null,
                        metadata == null ? null : metadata.lostRequestEndMillis());
            }

            @Override
            public boolean lostARequestOn(String thread) {
                if (recoveredOffload) {
                    return !durableComplete && journal.lostARequestOn(thread, true);
                }
                return !durableComplete && (lostThreads.contains(thread) || sameRun && journal.lostARequestOn(thread));
            }

            @Override
            public boolean evictedARequestOf(String traceId) {
                // Contiguous sequences cannot prove the presence of a request lost before sequencing.
                return metadata != null
                                && scope.run().equals(metadata.journalStatus().runId())
                                && metadata.offloadTraceLoss().matches(scope, traceId)
                        || !durableComplete
                                && (lostTraces.contains(traceId)
                                        || sameRun && journal.evictedARequestOf(traceId, currentArchive));
            }

            @Override
            public Long lostMillis(JournalSource source) {
                if (durableComplete) {
                    return null;
                }
                if (recoveredOffload) {
                    return journal.lostMillis(source, true);
                }
                Long durable = metadata == null ? null : metadata.lostBySource().get(source);
                return maximum(sameRun ? journal.lostMillis(source) : null, durable);
            }

            @Override
            public boolean sqlExecutionQualified() {
                if (sameRun) {
                    SqlCaptureScopes.Snapshot sql = journal.sqlCaptureScope();
                    return !sql.executions().isEmpty() && sql.preparations().isEmpty() && !sql.incomplete();
                }
                DurableJournalRunMetadata.SqlCaptureQualification sql =
                        metadata == null ? null : metadata.sqlCaptureQualification();
                return sql != null && sql.executionCount() > 0 && sql.preparationCount() == 0 && !sql.incomplete();
            }
        };
    }

    private static Long maximum(Long left, Long right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return Math.max(left, right);
    }

    private static final class BoundedEntries {

        private final int maxEvents;
        private final long maxBytes;
        private final TreeMap<Long, JournalEntry> entries = new TreeMap<>();
        private long bytes;

        private BoundedEntries(int maxEvents, long maxBytes) {
            this.maxEvents = Math.max(1, maxEvents);
            this.maxBytes = Math.max(0, maxBytes);
        }

        void add(JournalEntry entry) {
            JournalEntry replaced = entries.put(entry.sequence(), entry);
            if (replaced != null) {
                bytes -= replaced.estimatedBytes();
            }
            bytes += Math.max(0, entry.estimatedBytes());
            while (entries.size() > maxEvents || bytes > maxBytes) {
                JournalEntry oldest = entries.remove(entries.firstKey());
                bytes -= Math.max(0, oldest.estimatedBytes());
                if (oldest.sequence() == entry.sequence()) {
                    break;
                }
            }
        }

        long bytes() {
            return bytes;
        }

        List<JournalEntry> newestFirst() {
            List<JournalEntry> newest = new ArrayList<>(entries.values());
            Collections.reverse(newest);
            return List.copyOf(newest);
        }
    }
}
