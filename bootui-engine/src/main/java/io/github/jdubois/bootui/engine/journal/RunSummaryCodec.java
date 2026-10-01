package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.journal.JournalAggregates.AggregatesSnapshot;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.ExceptionGroupStats;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteStats;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RunStats;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.StatementStats;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.ThreadFamilyStats;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.TransactionalMethodStats;
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
 * buckets. When the summary exceeds the bound, the least-used entries of each aggregate are left out, halving how many
 * are kept until it fits, and the header counts what was left out.</p>
 */
final class RunSummaryCodec {

    private static final int MAGIC = 0x42555253;

    private static final int VERSION = 1;

    private RunSummaryCodec() {}

    /** Encodes {@code summary} within {@code maxBytes}, leaving out its least-used entries if it must. */
    static byte[] encode(RunSummary summary, int maxBytes) {
        AggregatesSnapshot full = summary.aggregates();
        byte[] bytes = encode(summary.header(), full, 0);
        int limit = largestDimension(full);
        while (bytes.length > maxBytes && limit > 0) {
            limit /= 2;
            AggregatesSnapshot kept = trim(full, limit);
            bytes = encode(summary.header(), kept, entries(full) - entries(kept));
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
        List<RouteStats> routes = in.list(() -> new RouteStats(
                in.string(),
                in.number(),
                List.of(in.number(), in.number(), in.number(), in.number(), in.number()),
                in.histogram(),
                in.sourceMap(),
                in.sourceMap(),
                in.stringMap(),
                in.number()));
        List<StatementStats> statements = in.list(
                () -> new StatementStats(in.string(), in.number(), in.number(), in.histogram(), in.stringMap()));
        List<ExceptionGroupStats> groups =
                in.list(() -> new ExceptionGroupStats(in.string(), in.string(), in.number(), in.stringMap()));
        List<TransactionalMethodStats> methods =
                in.list(() -> new TransactionalMethodStats(in.string(), in.number(), in.number(), in.histogram()));
        List<ThreadFamilyStats> families =
                in.list(() -> new ThreadFamilyStats(in.string(), in.sourceMap(), in.sourceMap()));
        Map<String, Long> overflowed = in.stringMap();
        return new RunSummary(
                header, new AggregatesSnapshot(routes, statements, groups, methods, families, run, overflowed));
    }

    private static byte[] encode(RunSummary.Header header, AggregatesSnapshot aggregates, int omitted) {
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
            body.string(route.route());
            body.number(route.requests());
            route.statusClasses().forEach(body::number);
            body.histogram(route.latency());
            body.sourceMap(route.childCounts());
            body.sourceMap(route.childNanos());
            body.stringMap(route.statements());
            body.number(route.connectionWaitNanos());
        }
        body.number(aggregates.statements().size());
        for (StatementStats statement : aggregates.statements()) {
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
        body.stringMap(aggregates.overflowed());

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
        out.number(body.table.size());
        body.table.keySet().forEach(out::text);
        out.bytes.writeBytes(body.bytes.toByteArray());
        return out.bytes.toByteArray();
    }

    /** The most entries any aggregate or nested count holds, where trimming starts halving. */
    private static int largestDimension(AggregatesSnapshot aggregates) {
        int largest = Math.max(
                Math.max(aggregates.routes().size(), aggregates.statements().size()),
                Math.max(
                        aggregates.exceptionGroups().size(),
                        Math.max(
                                aggregates.transactionalMethods().size(),
                                aggregates.threadFamilies().size())));
        for (RouteStats route : aggregates.routes()) {
            largest = Math.max(largest, route.statements().size());
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
                        route.connectionWaitNanos()));
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
                        group.groupId(), group.exceptionClass(), group.occurrences(), top(group.routes(), limit)));
        List<TransactionalMethodStats> methods = top(
                aggregates.transactionalMethods(), TransactionalMethodStats::transactions, limit, Function.identity());
        List<ThreadFamilyStats> families = top(
                aggregates.threadFamilies(),
                family -> family.events().values().stream()
                        .mapToLong(Long::longValue)
                        .sum(),
                limit,
                Function.identity());
        return new AggregatesSnapshot(
                routes, statements, groups, methods, families, aggregates.run(), aggregates.overflowed());
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
                + aggregates.threadFamilies().size();
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

        In(byte[] bytes) {
            this.bytes = bytes;
        }

        RunSummary.Header header() {
            int magic = 0;
            for (int i = 0; i < 4; i++) {
                magic = (magic << 8) | (next() & 0xFF);
            }
            int version = next();
            if (magic != MAGIC || version != VERSION) {
                throw new IllegalArgumentException("Not a run summary of version " + VERSION);
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
                    bytes.length);
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
