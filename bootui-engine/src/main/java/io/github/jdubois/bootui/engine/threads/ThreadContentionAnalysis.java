package io.github.jdubois.bootui.engine.threads;

import io.github.jdubois.bootui.core.dto.CapturedThreadDto;
import io.github.jdubois.bootui.core.dto.ThreadAnalysisReport;
import io.github.jdubois.bootui.core.dto.ThreadBlockerDto;
import io.github.jdubois.bootui.core.dto.ThreadCaptureSummary;
import io.github.jdubois.bootui.core.dto.ThreadCycleDto;
import io.github.jdubois.bootui.core.dto.ThreadStackGroupDto;
import io.github.jdubois.bootui.core.dto.ThreadStateCountDto;
import io.github.jdubois.bootui.core.dto.ThreadWaitDto;
import io.github.jdubois.bootui.engine.support.PagedList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Capture-local graph. An edge always comes from a supported waiter's explicit owner ID. */
final class ThreadContentionAnalysis {
    private ThreadContentionAnalysis() {}

    static ThreadAnalysisReport analyze(
            ThreadCaptureSummary summary, List<CapturedThreadDto> rows, Integer offset, Integer limit) {
        int evidenceLimit = Math.min(25, limit == null ? 200 : limit);
        Map<Long, CapturedThreadDto> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.put(row.thread().id(), row));
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, List<Long>> children = new HashMap<>();
        List<ThreadWaitDto> unresolved = new ArrayList<>();
        for (CapturedThreadDto row : rows) {
            if (row.thread().lockName() == null) {
                continue;
            }
            Long owner = row.thread().lockOwnerId();
            if (supported(row) && owner != null && byId.containsKey(owner)) {
                parent.put(row.thread().id(), owner);
                children.computeIfAbsent(owner, ignored -> new ArrayList<>())
                        .add(row.thread().id());
            } else {
                unresolved.add(wait(row, byId));
            }
        }
        List<ThreadBlockerDto> roots = new ArrayList<>();
        for (var entry : children.entrySet()) {
            if (parent.containsKey(entry.getKey())) {
                continue;
            }
            CapturedThreadDto owner = byId.get(entry.getKey());
            Reachability reach = descendants(List.of(entry.getKey()), children, byId, evidenceLimit);
            roots.add(new ThreadBlockerDto(
                    owner.observationId(),
                    owner.thread().id(),
                    owner.thread().name(),
                    entry.getValue().size(),
                    reach.count(),
                    reach.depth(),
                    owner.thread().stackTrace().isEmpty()
                            ? null
                            : owner.thread().stackTrace().get(0),
                    !summary.quality().populationComplete() || owner.thread().lockName() != null,
                    reach.ids(),
                    reach.count() > reach.ids().size()));
        }
        roots.sort(Comparator.comparingInt(ThreadBlockerDto::transitiveBlocked)
                .reversed()
                .thenComparing(
                        Comparator.comparingInt(ThreadBlockerDto::directBlocked).reversed())
                .thenComparingLong(ThreadBlockerDto::threadId));
        List<ThreadCycleDto> cycles = new ArrayList<>();
        Set<Long> done = new HashSet<>();
        for (Long start : parent.keySet().stream().sorted().toList()) {
            List<Long> path = new ArrayList<>();
            Map<Long, Integer> positions = new HashMap<>();
            Long current = start;
            while (current != null && !done.contains(current) && !positions.containsKey(current)) {
                positions.put(current, path.size());
                path.add(current);
                current = parent.get(current);
            }
            if (current != null && positions.containsKey(current)) {
                List<Long> cycle = path.subList(positions.get(current), path.size()).stream()
                        .sorted()
                        .toList();
                Reachability reach = descendants(cycle, children, byId, evidenceLimit);
                cycles.add(new ThreadCycleDto(
                        "DERIVED_SUPPORTED_WAITS",
                        cycle.size(),
                        cycle.stream()
                                .limit(evidenceLimit)
                                .map(id -> byId.get(id).observationId())
                                .toList(),
                        cycle.size() > evidenceLimit,
                        reach.count(),
                        reach.ids(),
                        reach.count() > reach.ids().size()));
            }
            done.addAll(path);
        }
        cycles.sort(Comparator.comparing(cycle -> cycle.participantIds().get(0)));
        unresolved.sort(Comparator.comparingInt(wait -> supportedActivity(wait.kind()) ? 0 : 1));
        List<ThreadStackGroupDto> groups = groups(rows, summary.id(), evidenceLimit);
        var rootPage = PagedList.from(roots, offset, limit);
        var cyclePage = PagedList.from(cycles, offset, limit);
        var unresolvedPage = PagedList.from(unresolved, offset, limit);
        var groupPage = PagedList.from(groups, offset, limit);
        return new ThreadAnalysisReport(
                summary,
                rootPage.items(),
                rootPage.page(),
                cyclePage.items(),
                cyclePage.page(),
                unresolvedPage.items(),
                unresolvedPage.page(),
                groupPage.items(),
                groupPage.page());
    }

    static boolean supported(CapturedThreadDto row) {
        return supportedActivity(row.activity());
    }

    private static boolean supportedActivity(String activity) {
        return activity.equals("MONITOR_ACQUISITION") || activity.equals("EXCLUSIVE_SYNCHRONIZER_ACQUISITION");
    }

    static ThreadWaitDto wait(CapturedThreadDto row, Map<Long, CapturedThreadDto> byId) {
        CapturedThreadDto owner = byId.get(row.thread().lockOwnerId());
        String reason = supported(row)
                ? (owner == null ? "Owner was not captured; ownership is incomplete." : "Observed acquisition wait.")
                : "This wait is not evidence of an exclusive acquisition dependency.";
        return new ThreadWaitDto(
                row.observationId(),
                owner == null ? null : owner.observationId(),
                row.observationId(),
                row.activity(),
                row.thread().lockName(),
                reason);
    }

    private static Reachability descendants(
            List<Long> sources, Map<Long, List<Long>> children, Map<Long, CapturedThreadDto> byId, int evidenceLimit) {
        Set<Long> visited = new HashSet<>(sources);
        ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
        sources.forEach(id -> queue.add(new NodeDepth(id, 0)));
        List<String> ids = new ArrayList<>();
        int count = 0;
        int depth = 0;
        while (!queue.isEmpty()) {
            NodeDepth next = queue.removeFirst();
            for (Long child : children.getOrDefault(next.id(), List.of())) {
                if (visited.add(child)) {
                    count++;
                    depth = Math.max(depth, next.depth() + 1);
                    if (ids.size() < evidenceLimit) {
                        ids.add(byId.get(child).observationId());
                    }
                    queue.addLast(new NodeDepth(child, next.depth() + 1));
                }
            }
        }
        return new Reachability(count, depth, List.copyOf(ids));
    }

    private static List<ThreadStackGroupDto> groups(List<CapturedThreadDto> rows, String captureId, int evidenceLimit) {
        Map<List<String>, List<CapturedThreadDto>> groups = new LinkedHashMap<>();
        for (CapturedThreadDto row : rows) {
            if (ThreadSnapshot.hasComparableStack(row)) {
                groups.computeIfAbsent(row.thread().stackTrace(), ignored -> new ArrayList<>())
                        .add(row);
            }
        }
        List<ThreadStackGroupDto> result = new ArrayList<>();
        int groupId = 0;
        for (List<CapturedThreadDto> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            Map<String, Integer> states = new java.util.TreeMap<>();
            group.forEach(row -> states.merge(row.thread().state(), 1, Integer::sum));
            result.add(new ThreadStackGroupDto(
                    captureId + ":g" + groupId++,
                    group.get(0).observationId(),
                    group.size(),
                    group.stream().noneMatch(CapturedThreadDto::stackTruncated),
                    group.stream()
                            .limit(evidenceLimit)
                            .map(CapturedThreadDto::observationId)
                            .toList(),
                    group.size() > evidenceLimit,
                    states.entrySet().stream()
                            .map(entry -> new ThreadStateCountDto(entry.getKey(), entry.getValue()))
                            .toList()));
        }
        result.sort(
                Comparator.comparingInt(ThreadStackGroupDto::count).reversed().thenComparing(ThreadStackGroupDto::id));
        return result;
    }

    private record NodeDepth(long id, int depth) {}

    private record Reachability(int count, int depth, List<String> ids) {}
}
