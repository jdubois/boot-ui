package io.github.jdubois.bootui.engine.web;

import io.github.jdubois.bootui.core.dto.HttpRouteDto;
import io.github.jdubois.bootui.core.dto.HttpRouteWindowDto;
import io.github.jdubois.bootui.core.dto.HttpRoutesReport;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.engine.support.Percentiles;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Route performance rankings over the retained HTTP exchange window, computed once in the engine so Spring
 * MVC, Spring WebFlux and Quarkus return the same summary for the same exchanges.
 *
 * <p>It is a read over evidence BootUI already holds: the adapter hands over its retained exchanges and what
 * its exchange source knows about the window, and this service groups them by route (see {@link RouteLabel}),
 * counts status classes, and computes exact nearest-rank percentiles, cumulative time and shares. No request
 * filter, meter or second buffer is involved.</p>
 *
 * <p>Like the SQL Trace statement ranking, it returns the <em>union</em> of the top routes for each
 * {@link Criterion}, with every metric on each row, so re-sorting by any criterion yields that criterion's
 * exact top list while the response stays bounded no matter how many distinct paths the window holds. A
 * criterion only admits routes scoring above zero on it, and ties break on the route id, so the result never
 * depends on buffer order.</p>
 */
public final class HttpRouteSummaryService {

    /** Routes each criterion contributes when the caller does not ask for a number. */
    public static final int DEFAULT_TOP_PER_CRITERION = 25;

    /** Upper bound on routes each criterion may contribute, whatever the caller asks for. */
    public static final int MAX_TOP_PER_CRITERION = 100;

    /** The criteria a route summary can be ranked by. */
    public enum Criterion {
        REQUESTS(RouteAccumulator::requests),
        TOTAL_DURATION(RouteAccumulator::totalDurationMs),
        P95_DURATION(accumulator -> orZero(accumulator.percentile(95))),
        MAX_DURATION(accumulator -> orZero(accumulator.maxDurationMs())),
        ERROR_COUNT(RouteAccumulator::errorCount);

        private final ToDoubleFunction<RouteAccumulator> metric;

        Criterion(ToDoubleFunction<RouteAccumulator> metric) {
            this.metric = metric;
        }
    }

    /**
     * What the adapter's exchange source reports about its own window, which the exchanges cannot tell.
     *
     * @param bufferSize the buffer capacity, or {@code null} when the source does not expose it
     * @param evicted exchanges evicted since startup, or {@code null} when the source does not count them
     */
    public record ExchangeSource(Integer bufferSize, Long evicted) {

        /** A source that reports neither its capacity nor its evictions. */
        public static ExchangeSource unknown() {
            return new ExchangeSource(null, null);
        }
    }

    /**
     * Summarizes {@code captured} by route.
     *
     * @param captured every exchange the source currently retains, BootUI's own included
     * @param selfFilter decides which exchanges are BootUI's own and stay out of the summary
     * @param templates the application's declared routes, for exchanges without a framework template
     * @param source what the exchange source reports about its window
     * @param limit routes per criterion; {@code null} or non-positive means {@link #DEFAULT_TOP_PER_CRITERION},
     *     and anything above {@link #MAX_TOP_PER_CRITERION} is capped
     */
    public HttpRoutesReport summarize(
            List<CapturedHttpExchange> captured,
            HttpExchangesService.BootUiSelfPath selfFilter,
            RouteTemplateResolver templates,
            ExchangeSource source,
            Integer limit) {
        List<CapturedHttpExchange> exchanges = captured == null ? List.of() : captured;
        RouteTemplateResolver declared = templates == null ? RouteTemplateResolver.empty() : templates;
        ExchangeSource window = source == null ? ExchangeSource.unknown() : source;
        int topPerCriterion = topPerCriterion(limit);

        Map<String, RouteAccumulator> routes = new LinkedHashMap<>();
        int hiddenSelf = 0;
        int summarized = 0;
        int timed = 0;
        long totalDurationMs = 0;
        Long oldest = null;
        Long newest = null;
        for (CapturedHttpExchange exchange : exchanges) {
            if (HttpExchangesService.isSelfExchange(exchange, selfFilter)) {
                hiddenSelf++;
                continue;
            }
            summarized++;
            if (exchange.durationMs() != null) {
                timed++;
                totalDurationMs += Math.max(0, exchange.durationMs());
            }
            if (exchange.timestamp() != null) {
                long at = exchange.timestamp().toEpochMilli();
                oldest = oldest == null ? at : Math.min(oldest, at);
                newest = newest == null ? at : Math.max(newest, at);
            }
            RouteLabel label = HttpRoutes.labelOf(exchange, declared);
            routes.computeIfAbsent(label.id(), id -> new RouteAccumulator(label))
                    .add(exchange, label.source());
        }

        List<RouteAccumulator> all = new ArrayList<>(routes.values());
        Map<String, Set<String>> selected = new LinkedHashMap<>();
        for (Criterion criterion : Criterion.values()) {
            all.stream()
                    .filter(accumulator -> criterion.metric.applyAsDouble(accumulator) > 0)
                    .sorted(byMetric(criterion.metric))
                    .limit(topPerCriterion)
                    .forEach(accumulator -> selected.computeIfAbsent(accumulator.id(), id -> new LinkedHashSet<>())
                            .add(criterion.name()));
        }
        long denominator = totalDurationMs;
        List<HttpRouteDto> ranked = all.stream()
                .filter(accumulator -> selected.containsKey(accumulator.id()))
                .sorted(byMetric(Criterion.REQUESTS.metric))
                .map(accumulator -> accumulator.toDto(List.copyOf(selected.get(accumulator.id())), denominator))
                .toList();

        HttpRouteWindowDto windowDto = new HttpRouteWindowDto(
                exchanges.size(),
                window.bufferSize(),
                window.evicted(),
                hiddenSelf,
                summarized,
                timed,
                oldest,
                newest,
                totalDurationMs);
        boolean truncated = all.size() > ranked.size();
        return new HttpRoutesReport(
                true,
                null,
                windowDto,
                ranked,
                topPerCriterion,
                truncated,
                all.size(),
                notes(windowDto, declared, truncated, topPerCriterion, all.size()));
    }

    static int topPerCriterion(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_TOP_PER_CRITERION;
        }
        return Math.min(limit, MAX_TOP_PER_CRITERION);
    }

    /** Highest metric first, then route id ascending so ties never depend on buffer order or adapter. */
    private static Comparator<RouteAccumulator> byMetric(ToDoubleFunction<RouteAccumulator> metric) {
        return Comparator.comparingDouble(metric).reversed().thenComparing(RouteAccumulator::id);
    }

    private static List<String> notes(
            HttpRouteWindowDto window,
            RouteTemplateResolver templates,
            boolean truncated,
            int topPerCriterion,
            int distinctRoutes) {
        List<String> notes = new ArrayList<>();
        notes.add("Every figure covers only the " + window.summarizedExchanges() + " retained, visible "
                + (window.summarizedExchanges() == 1 ? "exchange" : "exchanges")
                + ". These are diagnostic evidence for this window, not lifetime or service-level metrics, and "
                + "a route with few requests has few samples behind its percentiles.");
        notes.add("Routes use the template the framework matched, then the single best route the application "
                + "declares, then the path with every value-like segment masked. Query strings and "
                + "path-parameter values are never used.");
        if (templates.isEmpty()) {
            notes.add("No declared route mappings were available, so a request without a framework template is "
                    + "grouped by its masked path.");
        }
        int untimed = window.summarizedExchanges() - window.timedExchanges();
        if (untimed > 0) {
            notes.add(untimed + (untimed == 1 ? " exchange carries" : " exchanges carry")
                    + " no duration, so it counts toward requests and status classes but not toward timings.");
        }
        if (window.evicted() == null) {
            notes.add("This exchange source does not count evictions, so how much older traffic has aged out of "
                    + "the window is not known.");
        }
        if (window.bufferSize() != null && window.retainedExchanges() >= window.bufferSize()) {
            notes.add("The exchange buffer is full, so each new request evicts the oldest one. Raise "
                    + "bootui.http-exchanges.max-exchanges to widen the window.");
        }
        if (truncated) {
            notes.add(distinctRoutes + " distinct routes were retained; only the top " + topPerCriterion
                    + " for each ranking criterion are shown.");
        }
        return notes;
    }

    private static double orZero(Long value) {
        return value == null ? 0 : value;
    }

    private static double percent(long part, long total) {
        if (total <= 0) {
            return 0;
        }
        return Math.round(10000.0 * part / total) / 100.0;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** Accumulates one route's exchanges. Durations are kept so percentiles are exact, not estimated. */
    private static final class RouteAccumulator {

        private final RouteLabel label;
        private final List<Long> durations = new ArrayList<>();
        private RouteLabel.Source strongestSource;
        private List<Long> sortedDurations;
        private long requests;
        private long status2xx;
        private long status3xx;
        private long status4xx;
        private long status5xx;
        private long statusOther;
        private long totalDurationMs;

        private RouteAccumulator(RouteLabel label) {
            this.label = label;
            this.strongestSource = label.source();
        }

        private void add(CapturedHttpExchange exchange, RouteLabel.Source source) {
            requests++;
            int status = exchange.status();
            if (status >= 200 && status < 300) {
                status2xx++;
            } else if (status >= 300 && status < 400) {
                status3xx++;
            } else if (status >= 400 && status < 500) {
                status4xx++;
            } else if (status >= 500 && status < 600) {
                status5xx++;
            } else {
                statusOther++;
            }
            if (exchange.durationMs() != null) {
                long duration = Math.max(0, exchange.durationMs());
                durations.add(duration);
                totalDurationMs += duration;
                sortedDurations = null;
            }
            if (source.ordinal() < strongestSource.ordinal()) {
                strongestSource = source;
            }
        }

        private String id() {
            return label.id();
        }

        private long requests() {
            return requests;
        }

        private long errorCount() {
            return status4xx + status5xx;
        }

        private long totalDurationMs() {
            return totalDurationMs;
        }

        private Long maxDurationMs() {
            return durations.isEmpty() ? null : sorted().get(sorted().size() - 1);
        }

        private Long percentile(int percentile) {
            return Percentiles.ofSorted(sorted(), percentile);
        }

        private List<Long> sorted() {
            if (sortedDurations == null) {
                sortedDurations = Percentiles.sortedAscending(durations);
            }
            return sortedDurations;
        }

        private HttpRouteDto toDto(List<String> topFor, long windowTotalDurationMs) {
            return new HttpRouteDto(
                    label.id(),
                    label.method(),
                    label.route(),
                    strongestSource.name(),
                    requests,
                    status2xx,
                    status3xx,
                    status4xx,
                    status5xx,
                    statusOther,
                    errorCount(),
                    durations.size(),
                    totalDurationMs,
                    durations.isEmpty() ? null : round((double) totalDurationMs / durations.size()),
                    percentile(50),
                    percentile(95),
                    percentile(99),
                    maxDurationMs(),
                    percent(totalDurationMs, windowTotalDurationMs),
                    topFor);
        }
    }
}
