package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.journal.JournalAggregates.AggregatesSnapshot;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.ExceptionGroupStats;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.ExecutionStats;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteAuthorization;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteOrm;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteResources;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteStats;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RunStats;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.StatementStats;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.ThreadFamilyStats;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.TransactionalMethodStats;
import io.github.jdubois.bootui.engine.model.EdgeDiff.EdgeRef;
import io.github.jdubois.bootui.engine.model.EdgeType;
import io.github.jdubois.bootui.engine.model.NodeType;
import io.github.jdubois.bootui.engine.model.ObservedEdge;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * Encodes a {@link RunSummary} into a compact byte array within a byte bound, and decodes it back
 * ({@code docs/PLAN-v2.md} §5.2). The run holder keeps only these arrays, which are JDK types, so a kept run pins no
 * class loader, and the bound is the array's exact length.
 *
 * <p>The encoding starts with the run's header, then a table of the summary's distinct strings, which entries
 * reference by index, then the aggregates. Numbers are variable-length, and histograms keep only their non-empty
 * buckets. When the summary exceeds the bound, the least-used entries of each aggregate and the least-observed edges are
 * left out, halving how many are kept until it fits, and the header counts what was left out, and how many edges.</p>
 *
 * <p>Version 13 adds the run's application to its header, so runs of different applications sharing a JVM are never
 * compared; an earlier summary reads with none, and is compared with any application, as before. Version 12 adds what
 * the run did outside the JVM ({@link RunSideEffects}, M5-7b) after the executions, within its
 * own byte budget of {@value #SIDE_EFFECTS_MAX_BYTES} bytes, trimmed per sensor before anything else; an earlier
 * summary reads with none, never with an empty set. Version 11 distinguishes DML targets from read-side tables in observed edges. Earlier summaries keep their
 * original edges, but table edges from them cannot be compared with version 11's meaning. Version 10 keeps only
 * literal-free SQL display shapes; version 8 and 9 fingerprints are sanitized on read too.</p>
 */
final class RunSummaryCodec {

    private static final int MAGIC = 0x42555253;

    private static final int VERSION = 13;

    /** The most bytes a summary's side effects take, so they never crowd out the aggregates. */
    static final int SIDE_EFFECTS_MAX_BYTES = 48 * 1024;

    private RunSummaryCodec() {}

    /** Encodes {@code summary} within {@code maxBytes}, leaving out its least-used entries if it must. */
    static byte[] encode(RunSummary summary, int maxBytes) {
        AggregatesSnapshot full = summary.aggregates();
        RunSideEffects sideEffects = fitSideEffects(summary.sideEffects(), SIDE_EFFECTS_MAX_BYTES);
        byte[] bytes = encode(summary.header(), full, 0, 0, sideEffects);
        int limit = largestDimension(full);
        while (bytes.length > maxBytes && limit > 0) {
            limit /= 2;
            AggregatesSnapshot kept = trim(full, limit);
            bytes = encode(
                    summary.header(),
                    kept,
                    entries(full) - entries(kept),
                    full.edges().size() - kept.edges().size(),
                    sideEffects);
        }
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("A run summary needs at least " + bytes.length + " bytes");
        }
        return bytes;
    }

    /** Reads only the header of an encoded summary. */
    static RunSummary.Header header(byte[] bytes) {
        return new In(bytes).header();
    }

    /** Decodes a summary {@link #encode} produced. */
    static RunSummary decode(byte[] bytes) {
        In in = new In(bytes);
        RunSummary.Header header = in.header();
        in.strings();
        RunStats run = new RunStats(
                in.sourceMap(),
                in.sourceMap(),
                in.optionalLong(),
                in.optionalLong(),
                in.number(),
                in.number(),
                in.number(),
                in.number());
        List<RouteStats> routes = in.list(in::route);
        List<StatementStats> statements = statementShapes(in.list(
                () -> new StatementStats(in.string(), in.number(), in.number(), in.histogram(), in.stringMap())));
        List<ExceptionGroupStats> groups = in.list(
                () -> new ExceptionGroupStats(in.string(), in.string(), in.string(), in.number(), in.stringMap()));
        List<TransactionalMethodStats> methods =
                in.list(() -> new TransactionalMethodStats(in.string(), in.number(), in.number(), in.histogram()));
        List<ThreadFamilyStats> families =
                in.list(() -> new ThreadFamilyStats(in.string(), in.sourceMap(), in.sourceMap()));
        List<ObservedEdge> edges = in.edges();
        Map<String, Long> overflowed = in.stringMap();
        if (in.version < 11) {
            overflowed = new LinkedHashMap<>(overflowed);
            overflowed.put(JournalAggregates.LEGACY_TABLE_EDGES, 1L);
        }
        boolean executionsRecorded = in.version >= 9 && in.number() != 0;
        List<ExecutionStats> executions = in.version >= 9
                ? in.list(() -> new ExecutionStats(In.SOURCES.get(in.string()), in.route()))
                : List.of();
        RunSideEffects sideEffects = in.version >= 12 ? in.sideEffects() : null;
        return new RunSummary(
                header,
                new AggregatesSnapshot(
                        routes,
                        statements,
                        groups,
                        methods,
                        families,
                        edges,
                        run,
                        overflowed,
                        executions,
                        executionsRecorded),
                sideEffects);
    }

    private static RouteStats readRoute(In in) {
        return new RouteStats(
                in.string(),
                in.number(),
                List.of(in.number(), in.number(), in.number(), in.number(), in.number()),
                in.histogram(),
                in.sourceMap(),
                in.sourceMap(),
                JournalTextExposure.statementCounts(in.stringMap()),
                in.number(),
                new RouteResources(
                        in.number(),
                        in.number(),
                        in.number(),
                        in.number(),
                        in.number(),
                        in.number(),
                        in.number(),
                        in.number(),
                        in.version >= 9 && in.number() != 0 ? in.histogram() : null),
                in.histogram(),
                in.number(),
                in.number(),
                new RouteAuthorization(in.number(), in.number(), in.number(), in.number(), in.number(), in.number()),
                new RouteOrm(in.number(), in.number(), in.number(), in.number(), in.number(), in.histogram()));
    }

    private static byte[] encode(
            RunSummary.Header header,
            AggregatesSnapshot aggregates,
            int omitted,
            int omittedEdges,
            RunSideEffects sideEffects) {
        Out body = new Out();
        RunStats run = aggregates.run();
        body.sourceMap(run.events());
        body.sourceMap(run.nanos());
        body.optionalLong(run.firstEpochMillis());
        body.optionalLong(run.lastEpochMillis());
        body.number(run.requests());
        body.number(run.failedRequests());
        body.number(run.unattributedRequests());
        body.number(run.openRequests());
        body.number(aggregates.routes().size());
        for (RouteStats route : aggregates.routes()) {
            writeRoute(body, route);
        }
        List<StatementStats> statements = statementShapes(aggregates.statements());
        body.number(statements.size());
        for (StatementStats statement : statements) {
            body.string(statement.fingerprint());
            body.number(statement.executions());
            body.number(statement.failures());
            body.histogram(statement.latency());
            body.stringMap(statement.callSites());
        }
        body.number(aggregates.exceptionGroups().size());
        for (ExceptionGroupStats group : aggregates.exceptionGroups()) {
            body.string(group.groupId());
            body.string(group.exceptionClass());
            body.string(group.signature());
            body.number(group.occurrences());
            body.stringMap(group.routes());
        }
        body.number(aggregates.transactionalMethods().size());
        for (TransactionalMethodStats method : aggregates.transactionalMethods()) {
            body.string(method.method());
            body.number(method.transactions());
            body.number(method.rollbacks());
            body.histogram(method.latency());
        }
        body.number(aggregates.threadFamilies().size());
        for (ThreadFamilyStats family : aggregates.threadFamilies()) {
            body.string(family.family());
            body.sourceMap(family.events());
            body.sourceMap(family.nanos());
        }
        body.number(aggregates.edges().size());
        for (ObservedEdge observed : aggregates.edges()) {
            EdgeRef edge = observed.edge();
            body.string(edge.fromType().name());
            body.string(edge.fromKey());
            body.string(edge.type().name());
            body.string(edge.toType().name());
            body.string(edge.toKey());
            body.number(observed.count());
            body.number(observed.firstSeenEpochMillis());
            body.number(observed.lastSeenEpochMillis());
        }
        body.stringMap(aggregates.overflowed());
        body.number(aggregates.executionsRecorded() ? 1 : 0);
        body.number(aggregates.executions().size());
        for (ExecutionStats execution : aggregates.executions()) {
            body.string(execution.source() == null ? null : execution.source().propertyName());
            writeRoute(body, execution.stats());
        }
        body.sideEffects(sideEffects);

        Out out = new Out();
        out.fixedInt(MAGIC);
        out.bytes.write(VERSION);
        out.text(header.runId());
        out.number(header.ordinal());
        out.number(header.startedAtEpochMillis());
        out.number(header.endedAtEpochMillis());
        out.number(header.requests());
        out.number(header.failedRequests());
        out.number(header.events());
        out.number(omitted);
        out.number(omittedEdges);
        out.runStart(header.runStart());
        out.optionalText(header.application());
        out.number(body.table.size());
        body.table.keySet().forEach(out::text);
        out.bytes.writeBytes(body.bytes.toByteArray());
        return out.bytes.toByteArray();
    }

    private static void writeRoute(Out body, RouteStats route) {
        body.string(route.route());
        body.number(route.requests());
        route.statusClasses().forEach(body::number);
        body.histogram(route.latency());
        body.sourceMap(route.childCounts());
        body.sourceMap(route.childNanos());
        body.stringMap(JournalTextExposure.statementCounts(route.statements()));
        body.number(route.connectionWaitNanos());
        RouteResources resources = route.resources();
        body.number(resources.measuredRequests());
        body.number(resources.partialRequests());
        body.number(resources.unmeasuredRequests());
        body.number(resources.cpuNanos());
        body.number(resources.allocatedBytes());
        body.number(resources.gcPauses());
        body.number(resources.requestsWithGcPause());
        body.number(resources.gcPauseNanos());
        body.number(resources.allocation() == null ? 0 : 1);
        if (resources.allocation() != null) {
            body.histogram(resources.allocation());
        }
        body.histogram(route.warmLatency());
        body.number(route.cacheMisses());
        body.number(route.aiTokens());
        RouteAuthorization authorization = route.authorization();
        body.number(authorization.anonymous());
        body.number(authorization.authenticated());
        body.number(authorization.none());
        body.number(authorization.unknown());
        body.number(authorization.anonymousSuccesses());
        body.number(authorization.denied());
        RouteOrm orm = route.orm();
        body.number(orm.requests());
        body.number(orm.flushes());
        body.number(orm.autoFlushes());
        body.number(orm.entityRequests());
        body.number(orm.entities());
        body.histogram(orm.time());
    }

    /**
     * {@code sideEffects} within {@code maxBytes}: each sensor's least frequent keys left out, halving how many are kept
     * until it fits, and counted in that sensor's omitted keys, so a comparison knows the kept keys are a subset.
     */
    static RunSideEffects fitSideEffects(RunSideEffects sideEffects, int maxBytes) {
        if (sideEffects == null || sideEffectsBytes(sideEffects) <= maxBytes) {
            return sideEffects;
        }
        Map<String, Integer> perSensor = new LinkedHashMap<>();
        sideEffects.keys().forEach(key -> perSensor.merge(key.sensor(), 1, Integer::sum));
        int limit =
                perSensor.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        RunSideEffects kept = sideEffects;
        while (limit > 0 && sideEffectsBytes(kept) > maxBytes) {
            limit /= 2;
            kept = keepTop(sideEffects, limit);
        }
        return kept;
    }

    private static RunSideEffects keepTop(RunSideEffects sideEffects, int limit) {
        Map<String, Integer> kept = new HashMap<>();
        Map<String, Long> omitted = new HashMap<>();
        List<RunSideEffects.Key> keys = new ArrayList<>();
        List<RunSideEffects.Key> sorted = new ArrayList<>(sideEffects.keys());
        sorted.sort(Comparator.comparingLong(RunSideEffects.Key::count).reversed());
        for (RunSideEffects.Key key : sorted) {
            int count = kept.getOrDefault(key.sensor(), 0);
            if (count < limit) {
                kept.put(key.sensor(), count + 1);
                keys.add(key);
            } else {
                omitted.merge(key.sensor(), 1L, Long::sum);
            }
        }
        List<RunSideEffects.Sensor> sensors = new ArrayList<>();
        for (RunSideEffects.Sensor sensor : sideEffects.sensors()) {
            sensors.add(new RunSideEffects.Sensor(
                    sensor.id(),
                    sensor.reason(),
                    sensor.startupReason(),
                    sensor.omittedKeys() + omitted.getOrDefault(sensor.id(), 0L)));
        }
        return new RunSideEffects(sideEffects.unavailableReason(), sideEffects.routesHidden(), sensors, keys);
    }

    /** The bytes {@code sideEffects} takes alone, its strings included as if none were shared. */
    static int sideEffectsBytes(RunSideEffects sideEffects) {
        Out out = new Out();
        out.sideEffects(sideEffects);
        int bytes = out.bytes.size();
        for (String text : out.table.keySet()) {
            bytes += text.getBytes(StandardCharsets.UTF_8).length + 2;
        }
        return bytes;
    }

    /** The most entries any aggregate or nested count holds, where trimming starts halving. */
    private static List<StatementStats> statementShapes(List<StatementStats> statements) {
        Map<String, StatementStats> shapes = new LinkedHashMap<>();
        for (StatementStats statement : statements) {
            String shape = JournalTextExposure.statementShape(statement.fingerprint());
            StatementStats previous = shapes.get(shape);
            if (previous == null) {
                shapes.put(
                        shape,
                        new StatementStats(
                                shape,
                                statement.executions(),
                                statement.failures(),
                                statement.latency().copy(),
                                statement.callSites()));
            } else {
                previous.latency().merge(statement.latency());
                Map<String, Long> sites = new LinkedHashMap<>(previous.callSites());
                statement.callSites().forEach((site, count) -> sites.merge(site, count, Long::sum));
                shapes.put(
                        shape,
                        new StatementStats(
                                shape,
                                previous.executions() + statement.executions(),
                                previous.failures() + statement.failures(),
                                previous.latency(),
                                Collections.unmodifiableMap(sites)));
            }
        }
        return List.copyOf(shapes.values());
    }

    private static int largestDimension(AggregatesSnapshot aggregates) {
        int largest = Math.max(
                Math.max(aggregates.routes().size(), aggregates.statements().size()),
                Math.max(
                        aggregates.exceptionGroups().size(),
                        Math.max(
                                aggregates.transactionalMethods().size(),
                                Math.max(
                                        aggregates.threadFamilies().size(),
                                        aggregates.edges().size()))));
        for (RouteStats route : aggregates.routes()) {
            largest = Math.max(largest, route.statements().size());
        }
        largest = Math.max(largest, aggregates.executions().size());
        for (ExecutionStats execution : aggregates.executions()) {
            largest = Math.max(largest, execution.stats().statements().size());
        }
        for (StatementStats statement : aggregates.statements()) {
            largest = Math.max(largest, statement.callSites().size());
        }
        for (ExceptionGroupStats group : aggregates.exceptionGroups()) {
            largest = Math.max(largest, group.routes().size());
        }
        return largest;
    }

    private static AggregatesSnapshot trim(AggregatesSnapshot aggregates, int limit) {
        List<RouteStats> routes = top(
                aggregates.routes(),
                RouteStats::requests,
                limit,
                route -> new RouteStats(
                        route.route(),
                        route.requests(),
                        route.statusClasses(),
                        route.latency(),
                        route.childCounts(),
                        route.childNanos(),
                        top(route.statements(), limit),
                        route.connectionWaitNanos(),
                        route.resources(),
                        route.warmLatency(),
                        route.cacheMisses(),
                        route.aiTokens(),
                        route.authorization(),
                        route.orm()));
        List<StatementStats> statements = top(
                aggregates.statements(),
                StatementStats::executions,
                limit,
                statement -> new StatementStats(
                        statement.fingerprint(),
                        statement.executions(),
                        statement.failures(),
                        statement.latency(),
                        top(statement.callSites(), limit)));
        List<ExceptionGroupStats> groups = top(
                aggregates.exceptionGroups(),
                ExceptionGroupStats::occurrences,
                limit,
                group -> new ExceptionGroupStats(
                        group.groupId(),
                        group.exceptionClass(),
                        group.signature(),
                        group.occurrences(),
                        top(group.routes(), limit)));
        List<TransactionalMethodStats> methods = top(
                aggregates.transactionalMethods(), TransactionalMethodStats::transactions, limit, Function.identity());
        List<ThreadFamilyStats> families = top(
                aggregates.threadFamilies(),
                family -> family.events().values().stream()
                        .mapToLong(Long::longValue)
                        .sum(),
                limit,
                Function.identity());
        List<ObservedEdge> edges = top(aggregates.edges(), ObservedEdge::count, limit, Function.identity());
        List<ExecutionStats> executions = top(
                aggregates.executions(),
                value -> value.stats().requests(),
                limit,
                value -> new ExecutionStats(value.source(), trimRoute(value.stats(), limit)));
        return new AggregatesSnapshot(
                routes,
                statements,
                groups,
                methods,
                families,
                edges,
                aggregates.run(),
                aggregates.overflowed(),
                executions,
                aggregates.executionsRecorded());
    }

    private static RouteStats trimRoute(RouteStats route, int limit) {
        return new RouteStats(
                route.route(),
                route.requests(),
                route.statusClasses(),
                route.latency(),
                route.childCounts(),
                route.childNanos(),
                top(route.statements(), limit),
                route.connectionWaitNanos(),
                route.resources(),
                route.warmLatency(),
                route.cacheMisses(),
                route.aiTokens(),
                route.authorization(),
                route.orm());
    }

    private static <T> List<T> top(List<T> entries, ToLongFunction<T> weight, int limit, Function<T, T> nested) {
        return entries.stream()
                .sorted(Comparator.comparingLong(weight).reversed())
                .limit(limit)
                .map(nested)
                .toList();
    }

    private static Map<String, Long> top(Map<String, Long> counts, int limit) {
        if (counts.size() <= limit) {
            return counts;
        }
        Map<String, Long> kept = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .forEach(entry -> kept.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(kept);
    }

    /** Every entry of every aggregate, nested counts included, so the header can say how many were left out. */
    private static int entries(AggregatesSnapshot aggregates) {
        int entries = aggregates.routes().size()
                + aggregates.statements().size()
                + aggregates.exceptionGroups().size()
                + aggregates.transactionalMethods().size()
                + aggregates.threadFamilies().size()
                + aggregates.edges().size()
                + aggregates.executions().size();
        for (ExecutionStats execution : aggregates.executions()) {
            entries += execution.stats().statements().size();
        }
        for (RouteStats route : aggregates.routes()) {
            entries += route.statements().size();
        }
        for (StatementStats statement : aggregates.statements()) {
            entries += statement.callSites().size();
        }
        for (ExceptionGroupStats group : aggregates.exceptionGroups()) {
            entries += group.routes().size();
        }
        return entries;
    }

    private static final class Out {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final Map<String, Integer> table = new LinkedHashMap<>();

        void fixedInt(int value) {
            bytes.write(value >>> 24);
            bytes.write(value >>> 16);
            bytes.write(value >>> 8);
            bytes.write(value);
        }

        /** An unsigned LEB128 number; negative values, which the aggregates never hold, take ten bytes. */
        void number(long value) {
            while ((value & ~0x7FL) != 0) {
                bytes.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            bytes.write((int) value);
        }

        void optionalLong(Long value) {
            number(value == null ? 0 : value + 1);
        }

        void text(String value) {
            byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
            number(utf8.length);
            bytes.write(utf8, 0, utf8.length);
        }

        /** A reference into the string table, {@code 0} for {@code null}. */
        void string(String value) {
            if (value == null) {
                number(0);
                return;
            }
            Integer index = table.get(value);
            if (index == null) {
                index = table.size();
                table.put(value, index);
            }
            number(index + 1L);
        }

        /** A run's start, written in the header with plain texts so the header reads without the string table. */
        void runStart(RunStart start) {
            if (start == null) {
                number(0);
                return;
            }
            number(1);
            optionalLong(start.readyNanos());
            number(start.slowestSteps().size());
            for (StartupStepTiming step : start.slowestSteps()) {
                text(step.name());
                optionalText(step.bean());
                number(step.durationNanos());
            }
            ComparabilityFacts facts = start.facts();
            texts(facts.activeProfiles());
            number(facts.dataSources().size());
            facts.dataSources().forEach((name, shape) -> {
                text(name);
                text(shape);
            });
            text(facts.cacheType());
            number(facts.tracing() ? 1 : 0);
            texts(facts.journalSources());
        }

        void optionalText(String value) {
            if (value == null) {
                number(0);
            } else {
                number(1);
                text(value);
            }
        }

        void texts(List<String> values) {
            number(values.size());
            values.forEach(this::text);
        }

        void stringMap(Map<String, Long> counts) {
            number(counts.size());
            counts.forEach((key, count) -> {
                string(key);
                number(count);
            });
        }

        void sourceMap(Map<JournalSource, Long> counts) {
            number(counts.size());
            counts.forEach((source, count) -> {
                string(source.propertyName());
                number(count);
            });
        }

        /** A run's side effects, {@code 0} for none (M5-7b). */
        void sideEffects(RunSideEffects sideEffects) {
            if (sideEffects == null) {
                number(0);
                return;
            }
            number(1);
            string(sideEffects.unavailableReason());
            number(sideEffects.routesHidden() ? 1 : 0);
            number(sideEffects.sensors().size());
            for (RunSideEffects.Sensor sensor : sideEffects.sensors()) {
                string(sensor.id());
                string(sensor.reason());
                string(sensor.startupReason());
                number(sensor.omittedKeys());
            }
            number(sideEffects.keys().size());
            for (RunSideEffects.Key key : sideEffects.keys()) {
                string(key.sensor());
                string(key.kind());
                string(key.target());
                string(key.scope());
                string(key.owner());
                string(key.client());
                number(key.count());
            }
        }

        void histogram(LatencyHistogram histogram) {
            number(histogram.count());
            number(histogram.totalMicros());
            number(histogram.maxMicros());
            int nonEmpty = 0;
            for (int i = 0; i < LatencyHistogram.BUCKETS; i++) {
                if (histogram.bucketCount(i) != 0) {
                    nonEmpty++;
                }
            }
            number(nonEmpty);
            int previous = 0;
            for (int i = 0; i < LatencyHistogram.BUCKETS; i++) {
                long count = histogram.bucketCount(i);
                if (count != 0) {
                    number(i - previous);
                    number(count);
                    previous = i;
                }
            }
        }
    }

    private static final class In {

        private static final Map<String, JournalSource> SOURCES = new HashMap<>();

        static {
            for (JournalSource source : JournalSource.values()) {
                SOURCES.put(source.propertyName(), source);
            }
        }

        private final byte[] bytes;
        private int position;
        private List<String> table = List.of();
        private int version;

        RouteStats route() {
            return readRoute(this);
        }

        In(byte[] bytes) {
            this.bytes = bytes;
        }

        RunSummary.Header header() {
            int magic = 0;
            for (int i = 0; i < 4; i++) {
                magic = (magic << 8) | (next() & 0xFF);
            }
            version = next();
            if (magic != MAGIC || version < 8 || version > VERSION) {
                throw new IllegalArgumentException("Not a supported run summary (versions 8 to " + VERSION + ")");
            }
            return new RunSummary.Header(
                    text(),
                    (int) number(),
                    number(),
                    number(),
                    number(),
                    number(),
                    number(),
                    (int) number(),
                    (int) number(),
                    bytes.length,
                    runStart(),
                    version >= 13 ? optionalText() : null);
        }

        RunStart runStart() {
            if (number() == 0) {
                return null;
            }
            Long readyNanos = optionalLong();
            int steps = (int) number();
            List<StartupStepTiming> slowest = new ArrayList<>(steps);
            for (int i = 0; i < steps; i++) {
                slowest.add(new StartupStepTiming(text(), optionalText(), number()));
            }
            List<String> profiles = texts();
            int dataSources = (int) number();
            Map<String, String> shapes = new LinkedHashMap<>();
            for (int i = 0; i < dataSources; i++) {
                shapes.put(text(), text());
            }
            String cacheType = text();
            boolean tracing = number() != 0;
            List<String> sources = texts();
            return new RunStart(
                    readyNanos, slowest, new ComparabilityFacts(profiles, shapes, cacheType, tracing, sources));
        }

        String optionalText() {
            return number() == 0 ? null : text();
        }

        List<String> texts() {
            int size = (int) number();
            List<String> values = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                values.add(text());
            }
            return values;
        }

        void strings() {
            int size = (int) number();
            List<String> strings = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                strings.add(text());
            }
            table = strings;
        }

        private int next() {
            if (position >= bytes.length) {
                throw new IllegalArgumentException("The run summary ends early");
            }
            return bytes[position++];
        }

        long number() {
            long value = 0;
            int shift = 0;
            while (true) {
                int b = next();
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
                if (shift > 63) {
                    throw new IllegalArgumentException("A run summary number is too long");
                }
            }
        }

        Long optionalLong() {
            long value = number();
            return value == 0 ? null : value - 1;
        }

        String text() {
            int length = (int) number();
            if (length < 0 || position + length > bytes.length) {
                throw new IllegalArgumentException("The run summary ends early");
            }
            String value = new String(bytes, position, length, StandardCharsets.UTF_8);
            position += length;
            return value;
        }

        String string() {
            long index = number();
            return index == 0 ? null : table.get((int) index - 1);
        }

        <T> List<T> list(Supplier<T> reader) {
            int size = (int) number();
            List<T> values = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                values.add(reader.get());
            }
            return values;
        }

        Map<String, Long> stringMap() {
            int size = (int) number();
            Map<String, Long> counts = new LinkedHashMap<>();
            for (int i = 0; i < size; i++) {
                counts.put(string(), number());
            }
            return Collections.unmodifiableMap(counts);
        }

        /** Counts per source; a source this version does not know, from a newer summary, is skipped. */
        Map<JournalSource, Long> sourceMap() {
            int size = (int) number();
            Map<JournalSource, Long> counts = new EnumMap<>(JournalSource.class);
            for (int i = 0; i < size; i++) {
                JournalSource source = SOURCES.get(string());
                long count = number();
                if (source != null) {
                    counts.put(source, count);
                }
            }
            return Collections.unmodifiableMap(counts);
        }

        /** Observed edges; an edge naming a node or edge type this version does not know is skipped. */
        List<ObservedEdge> edges() {
            int size = (int) number();
            List<ObservedEdge> edges = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                NodeType fromType = constant(NodeType.class, string());
                String fromKey = string();
                EdgeType type = constant(EdgeType.class, string());
                NodeType toType = constant(NodeType.class, string());
                String toKey = string();
                long count = number();
                long first = number();
                long last = number();
                if (fromType != null && type != null && toType != null && fromKey != null && toKey != null) {
                    edges.add(
                            new ObservedEdge(new EdgeRef(fromType, fromKey, type, toType, toKey), count, first, last));
                }
            }
            return edges;
        }

        private static <E extends Enum<E>> E constant(Class<E> type, String name) {
            if (name == null) {
                return null;
            }
            try {
                return Enum.valueOf(type, name);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }

        /** A run's side effects, {@code null} for none; a key missing a part is skipped. */
        RunSideEffects sideEffects() {
            if (number() == 0) {
                return null;
            }
            String unavailable = string();
            boolean routesHidden = number() != 0;
            int sensorCount = (int) number();
            List<RunSideEffects.Sensor> sensors = new ArrayList<>(Math.max(0, sensorCount));
            for (int i = 0; i < sensorCount; i++) {
                String id = string();
                String reason = string();
                String startupReason = string();
                long omitted = number();
                if (id != null) {
                    sensors.add(new RunSideEffects.Sensor(id, reason, startupReason, omitted));
                }
            }
            int keyCount = (int) number();
            List<RunSideEffects.Key> keys = new ArrayList<>(Math.max(0, keyCount));
            for (int i = 0; i < keyCount; i++) {
                String sensor = string();
                String kind = string();
                String target = string();
                String scope = string();
                String owner = string();
                String client = string();
                long count = number();
                if (sensor != null && kind != null && target != null && scope != null && owner != null) {
                    keys.add(new RunSideEffects.Key(sensor, kind, target, scope, owner, client, count));
                }
            }
            return new RunSideEffects(unavailable, routesHidden, sensors, keys);
        }

        LatencyHistogram histogram() {
            long count = number();
            long total = number();
            long max = number();
            int nonEmpty = (int) number();
            long[] counts = new long[LatencyHistogram.BUCKETS];
            int bucket = 0;
            for (int i = 0; i < nonEmpty; i++) {
                bucket += (int) number();
                counts[bucket] = number();
            }
            return LatencyHistogram.restore(counts, count, total, max);
        }
    }
}
