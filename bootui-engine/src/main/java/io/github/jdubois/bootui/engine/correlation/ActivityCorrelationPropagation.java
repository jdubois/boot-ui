package io.github.jdubois.bootui.engine.correlation;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Propagates an inbound request's correlation lookup identities onto the Live Activity entries that are
 * <em>already</em> correlated with that request.
 *
 * <p>This adds no correlation of its own: it walks the {@code parentId} relationship each adapter has
 * already established from retained trace evidence, so filtering by one identifier returns the owning
 * request plus exactly the children BootUI already nests under it. Background, messaging and otherwise
 * uncorrelated activity keeps no identifier, which is the honest answer rather than a heuristic guess.</p>
 *
 * <p>Only the opaque lookup identities travel down the chain — never the raw identifier and never its
 * masked rendering — so a large activity response cannot become a second, broader copy of the identifier
 * values.</p>
 */
public final class ActivityCorrelationPropagation {

    /**
     * Depth bound when walking up a {@code parentId} chain. Real chains are one level deep (child →
     * request); the bound simply keeps a malformed or cyclic chain from costing more than a constant.
     */
    private static final int MAX_PARENT_DEPTH = 8;

    private ActivityCorrelationPropagation() {}

    /**
     * Returns the entries with every correlated child stamped with its owning request's lookup identities.
     *
     * <p>Entries that already carry identities (the requests themselves) and entries with no resolvable
     * identifier-bearing ancestor are returned unchanged, so this is a no-op for an application that sends
     * no correlation headers.</p>
     *
     * @param entries merged activity entries in any order, or {@code null}
     * @return a new list in the same order, never {@code null}
     */
    public static List<ActivityEntryDto> propagate(List<ActivityEntryDto> entries) {
        if (entries == null || entries.isEmpty()) {
            return entries == null ? List.of() : entries;
        }
        Map<String, ActivityEntryDto> byId = new HashMap<>();
        boolean anyIdentifiers = false;
        for (ActivityEntryDto entry : entries) {
            if (entry == null) {
                continue;
            }
            if (entry.id() != null) {
                byId.putIfAbsent(entry.id(), entry);
            }
            anyIdentifiers = anyIdentifiers || !entry.correlationLookupIds().isEmpty();
        }
        if (!anyIdentifiers) {
            return entries;
        }
        List<ActivityEntryDto> result = new ArrayList<>(entries.size());
        for (ActivityEntryDto entry : entries) {
            result.add(entry == null ? null : stamp(entry, byId));
        }
        return result;
    }

    private static ActivityEntryDto stamp(ActivityEntryDto entry, Map<String, ActivityEntryDto> byId) {
        if (!entry.correlationLookupIds().isEmpty() || entry.parentId() == null) {
            return entry;
        }
        List<String> inherited = ancestorLookupIds(entry, byId);
        return inherited.isEmpty() ? entry : entry.withCorrelation(entry.correlationIds(), inherited);
    }

    private static List<String> ancestorLookupIds(ActivityEntryDto entry, Map<String, ActivityEntryDto> byId) {
        Set<String> visited = new HashSet<>();
        visited.add(entry.id());
        String parentId = entry.parentId();
        for (int depth = 0; depth < MAX_PARENT_DEPTH && parentId != null && visited.add(parentId); depth++) {
            ActivityEntryDto parent = byId.get(parentId);
            if (parent == null) {
                return List.of();
            }
            if (!parent.correlationLookupIds().isEmpty()) {
                return parent.correlationLookupIds();
            }
            parentId = parent.parentId();
        }
        return List.of();
    }
}
