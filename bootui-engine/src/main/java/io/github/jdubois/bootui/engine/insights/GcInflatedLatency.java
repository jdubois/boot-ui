package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.GcPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.resources.GcPauseRange;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code gc-inflated-latency} ({@code docs/PLAN-v2.md} §5.11): the share of a route's slowest requests during which a
 * stop-the-world pause completed, against the share of its other requests, with the pauses' total. A request's pauses
 * are the collections of pause collectors that completed while its work ran, joined to the {@code gc} source's events by
 * collector and id, never by time. It says a pause completed during a request, never that it caused its latency.
 *
 * <p>A route's slowest requests are its slowest tenth, and at least {@value #MIN_SLOW_REQUESTS}, so a route needs
 * {@value #MIN_MEASURED_REQUESTS} measured requests to be compared. It is observed when pauses completed during at least
 * {@value #MIN_SLOW_WITH_PAUSE} of them, and during at least twice as large a share of them as of the other requests.</p>
 */
public final class GcInflatedLatency implements Observation {

    public static final String KIND = "gc-inflated-latency";

    /** The fewest slowest requests compared. */
    static final int MIN_SLOW_REQUESTS = 5;

    /** The measured requests a route needs, so that its slowest requests have others to be compared with. */
    static final int MIN_MEASURED_REQUESTS = 2 * MIN_SLOW_REQUESTS;

    /** The slowest requests during which a pause must have completed. */
    static final int MIN_SLOW_WITH_PAUSE = 2;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Pauses during slow requests";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.REQUEST_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.GC, JournalSource.RESOURCES);
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        Map<String, RuntimeEvent> pauses = new HashMap<>();
        for (RuntimeEvent event : snapshot.collections()) {
            if (event.payload() instanceof GcPayload gc && gc.pause() && gc.collector() != null) {
                pauses.put(gc.collector() + '#' + gc.gcId(), event);
            }
        }
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.httpByRoute().entrySet()) {
            List<ProjectedRequest> measured = route.getValue().stream()
                    .filter(request -> request.resources() != null)
                    .sorted(Comparator.comparingLong(ProjectedRequest::durationNanos)
                            .reversed()
                            .thenComparing(ProjectedRequest::requestId))
                    .toList();
            eligible += measured.size();
            long withPause = measured.stream().filter(GcInflatedLatency::paused).count();
            if (withPause == 0) {
                continue;
            }
            if (measured.size() < MIN_MEASURED_REQUESTS) {
                // One pause during a young route's request is routine, so only a repeated one is worth naming.
                if (withPause >= MIN_SLOW_WITH_PAUSE) {
                    findings.add(insufficient(route.getKey(), measured, withPause));
                }
                continue;
            }
            int slowCount = Math.max(MIN_SLOW_REQUESTS, (measured.size() + 9) / 10);
            List<ProjectedRequest> slow = measured.subList(0, slowCount);
            List<ProjectedRequest> slowWithPause =
                    slow.stream().filter(GcInflatedLatency::paused).toList();
            long otherWithPause = withPause - slowWithPause.size();
            long others = measured.size() - slowCount;
            // slow share >= 2 × other share, compared without division.
            if (slowWithPause.size() < MIN_SLOW_WITH_PAUSE
                    || slowWithPause.size() * others < 2 * otherWithPause * slowCount) {
                continue;
            }
            findings.add(observed(route.getKey(), slowCount, slowWithPause, otherWithPause, others, pauses));
        }
        return new Evaluation(eligible, findings);
    }

    private static boolean paused(ProjectedRequest request) {
        return request.resources().gcPauses() > 0;
    }

    private Finding observed(
            String route,
            int slowCount,
            List<ProjectedRequest> slowWithPause,
            long otherWithPause,
            long others,
            Map<String, RuntimeEvent> pauses) {
        long pauseNanos = 0;
        long collections = 0;
        long unretained = 0;
        boolean truncated = false;
        List<List<String>> rows = new ArrayList<>();
        for (ProjectedRequest request : slowWithPause) {
            ResourceUsage usage = request.resources();
            truncated |= usage.gcPauseRangesTruncated();
            long requestNanos = 0;
            long requestUnretained = 0;
            Set<String> collectors = new TreeSet<>();
            for (GcPauseRange range : usage.gcPauseRanges()) {
                collectors.add(range.collector());
                for (long id = range.afterId() + 1; id <= range.lastId(); id++) {
                    RuntimeEvent pause = pauses.get(range.collector() + '#' + id);
                    if (pause == null) {
                        requestUnretained++;
                    } else {
                        requestNanos += Math.max(0, pause.durationNanos());
                    }
                }
            }
            pauseNanos += requestNanos;
            collections += usage.gcPauses();
            unretained += requestUnretained;
            rows.add(List.of(
                    request.requestId(),
                    InsightText.millis(request.durationNanos()),
                    String.valueOf(usage.gcPauses()),
                    requestUnretained > 0 && requestNanos == 0 ? "" : InsightText.millis(requestNanos),
                    String.join(", ", collectors)));
        }
        String sentence = "A stop-the-world pause completed during " + slowWithPause.size() + " of `" + route + "`'s "
                + slowCount + " slowest requests (" + percent(slowWithPause.size(), slowCount) + "), against "
                + percent(otherWithPause, others) + " of its other " + InsightText.counted(others, "request")
                + "; those pauses total " + InsightText.millis(pauseNanos) + " ms.";
        List<String> limitations = new ArrayList<>();
        limitations.add("A pause that completed during a request stopped it for at most the pause's length; how much"
                + " of the pause overlapped the request is not measured.");
        if (unretained > 0) {
            limitations.add(InsightText.counted(unretained, "collection") + " of " + collections
                    + " are no longer retained, so the pauses' total is a floor.");
        }
        if (truncated) {
            limitations.add("Some requests ran during more collections than are kept per request, so their counts are"
                    + " a floor.");
        }
        return new Finding(
                route,
                route,
                true,
                sentence,
                slowCount,
                slowWithPause.size(),
                List.of(
                        "Compare each pause with its request's duration in the request's timeline: a pause that is a"
                                + " small part of it does not explain the latency.",
                        "If pauses recur, check the Memory panel's allocation rate and heap size, and the route's"
                                + " allocated bytes in its requests."),
                slowWithPause.stream().map(ProjectedRequest::requestId).toList(),
                List.of("Request", "Duration (ms)", "Pauses", "Pause time (ms)", "Collectors"),
                rows,
                limitations);
    }

    private Finding insufficient(String route, List<ProjectedRequest> measured, long withPause) {
        return new Finding(
                route,
                route,
                false,
                "A pause completed during " + withPause + " of `" + route + "`'s "
                        + InsightText.counted(measured.size(), "measured request") + "; comparing its slowest requests"
                        + " with the others needs " + MIN_MEASURED_REQUESTS + ".",
                measured.size(),
                withPause,
                List.of("Exercise the route more, then refresh."),
                measured.stream()
                        .filter(GcInflatedLatency::paused)
                        .map(ProjectedRequest::requestId)
                        .toList(),
                List.of("Request", "Duration (ms)", "Pauses"),
                measured.stream()
                        .filter(GcInflatedLatency::paused)
                        .map(request -> List.of(
                                request.requestId(),
                                InsightText.millis(request.durationNanos()),
                                String.valueOf(request.resources().gcPauses())))
                        .toList(),
                List.of());
    }

    private static String percent(long part, long whole) {
        return whole == 0 ? "0 %" : String.format(Locale.ROOT, "%d %%", Math.round(100.0 * part / whole));
    }
}
