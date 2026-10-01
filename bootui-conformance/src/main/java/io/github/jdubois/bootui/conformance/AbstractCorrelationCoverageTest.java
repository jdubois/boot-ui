package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.CorrelationCoverage.Entry;
import io.github.jdubois.bootui.conformance.CorrelationCoverage.Report;
import io.github.jdubois.bootui.conformance.CorrelationCoverage.TypeCoverage;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Cross-stack correlation coverage scenario for {@code docs/PLAN-v2.md} §5.1.
 *
 * <p>A concrete runner boots its sample app and declares a few application routes. After one warm-up call per
 * route, the scenario runs three phases, each measured in its own window:</p>
 *
 * <ul>
 *   <li><b>paced</b>: identical requests spaced out, as when a developer clicks through the application;</li>
 *   <li><b>back-to-back</b>: identical requests sent one after another without a pause, as a test loop does;</li>
 *   <li><b>simultaneous</b>: identical requests released at the same instant, as parallel browser or test calls
 *       are.</li>
 * </ul>
 *
 * <p>Correlation that re-derives a request's identity after the fact from its method, path, and time cannot tell
 * close identical requests apart, so the last two phases show where it breaks.</p>
 *
 * <p>It then reads Live Activity once and measures, per event type, how much request-thread work is nested under
 * its request ({@link CorrelationCoverage}). The Markdown report is written to
 * {@code target/correlation-coverage/<runtime>.md}, beside the raw feed it was computed from.</p>
 *
 * <p>In every phase the scenario enforces the floors a runner declares in {@link #minimumNestedShares(Phase)}, and
 * that no child is nested under a request that was not running when it happened, the signature of a context leaked
 * from an earlier request. It reads the profile of one request per route that nested request-thread work, and
 * requires it not to be approximate. A runner that declares {@link #unownedThreadPattern()} calls a route whose work
 * runs on an executor the application did not wrap: that work must be reported, and never nested under a request.
 * A runner that declares its {@link #tracing()} requires either that every request carries its own trace id, however
 * close identical requests are, or that none does, so that its floors prove correlation without tracing.</p>
 */
public abstract class AbstractCorrelationCoverageTest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final int MAX_FEED_READS = 10;

    /**
     * Pause after each phase before the next one starts. Children reported shortly after their request returned, such
     * as an acknowledged message send, still count in their request's phase.
     */
    private static final long SETTLE_MILLIS = 250;

    /** One application route the scenario calls. */
    public record Traffic(String path, Map<String, String> headers, int concurrency) {

        public static Traffic anonymous(String path, int concurrency) {
            return new Traffic(path, Map.of(), concurrency);
        }

        public static Traffic basicAuth(String path, String username, String password, int concurrency) {
            String token =
                    Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            return new Traffic(path, Map.of("Authorization", "Basic " + token), concurrency);
        }
    }

    /** Scenario phases, each measured in its own window. */
    public enum Phase {
        PACED("Paced requests"),
        BACK_TO_BACK("Back-to-back requests"),
        SIMULTANEOUS("Simultaneous identical requests");

        private final String title;

        Phase(String title) {
            this.title = title;
        }
    }

    /** Base URL of the booted app under test, without a trailing slash. */
    protected abstract String baseUrl();

    /** File-name-safe runtime label, for example {@code spring-mvc}. */
    protected abstract String runtimeLabel();

    /** Application routes to call. Each must complete on a request-serving thread. */
    protected abstract List<Traffic> traffic();

    /** Names of this runtime's request-serving threads. */
    protected abstract Pattern requestThreadPattern();

    /** Rounds per phase. Each round calls every route {@link Traffic#concurrency()} times. */
    protected int rounds() {
        return 2;
    }

    /** Pause between two paced requests, wider than any after-the-fact matching slack. */
    protected long pacedGapMillis() {
        return 150;
    }

    /**
     * Minimum nested share per child type in a phase, checked only when that type was observed. Empty in M0: the
     * scenario records a baseline without enforcing one.
     */
    protected Map<String, Double> minimumNestedShares(Phase phase) {
        return Map.of();
    }

    /**
     * Thread names of the executor one route hands its work to without propagating any context, or {@code null} when
     * no route does. That work must be reported, and never nested under a request.
     */
    protected Pattern unownedThreadPattern() {
        return null;
    }

    /** Whether the runner's application traces its requests, which the scenario then checks on every request. */
    public enum Tracing {
        /** Not checked. */
        UNCHECKED,
        /** Every request carries a trace id. */
        ON,
        /** No request carries a trace id. */
        OFF
    }

    protected Tracing tracing() {
        return Tracing.UNCHECKED;
    }

    @Test
    void measuresRequestCorrelationCoverage() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        List<Traffic> traffic = traffic();
        assertThat(traffic).as("correlation scenario routes").isNotEmpty();
        int perPhase =
                rounds() * traffic.stream().mapToInt(Traffic::concurrency).sum();

        for (Traffic route : traffic) {
            send(client, route);
        }
        Thread.sleep(SETTLE_MILLIS);

        Map<Phase, long[]> windows = new EnumMap<>(Phase.class);
        long previousEnd = -1;
        Phase previous = null;
        for (Phase phase : Phase.values()) {
            if (previous != null) {
                Thread.sleep(SETTLE_MILLIS);
            }
            long start = nextMillisecond();
            if (previous != null) {
                windows.put(previous, new long[] {windows.get(previous)[0], previousEnd, start});
            }
            for (int round = 0; round < rounds(); round++) {
                for (Traffic route : traffic) {
                    if (phase == Phase.SIMULTANEOUS) {
                        sendSimultaneously(client, route);
                        continue;
                    }
                    for (int i = 0; i < route.concurrency(); i++) {
                        send(client, route);
                        if (phase == Phase.PACED) {
                            Thread.sleep(pacedGapMillis());
                        }
                    }
                }
            }
            previousEnd = nextMillisecond();
            previous = phase;
            windows.put(phase, new long[] {start, previousEnd, previousEnd + SETTLE_MILLIS});
        }

        FeedRead feed = readFeed(windows.get(Phase.PACED)[0], Phase.values().length * perPhase);
        StringBuilder markdown = new StringBuilder("# Correlation coverage: " + runtimeLabel() + "\n\n" + perPhase
                + " requests per phase: " + rounds() + " rounds over " + traffic.size() + " routes.\n");
        Map<Phase, Report> reports = new EnumMap<>(Phase.class);
        for (Phase phase : Phase.values()) {
            long[] window = windows.get(phase);
            Report report = CorrelationCoverage.measure(
                    feed.entries(), window[0], window[1], window[2], requestThreadPattern(), unownedThreadPattern());
            reports.put(phase, report);
            markdown.append("\n## ").append(phase.title).append("\n\n").append(report.toMarkdown());
        }
        Map<Phase, List<ProfileRead>> profiles = new EnumMap<>(Phase.class);
        for (Phase phase : Phase.values()) {
            long[] window = windows.get(phase);
            List<ProfileRead> read = readProfiles(feed.entries(), window[0], window[1]);
            profiles.put(phase, read);
            markdown.append("\n")
                    .append(phase.title)
                    .append(": ")
                    .append(read.size())
                    .append(" profiles read, ")
                    .append(read.stream().filter(ProfileRead::approximate).count())
                    .append(" approximate.\n");
        }
        writeFile(runtimeLabel() + "-feed.json", feed.body());

        JsonNode journal = readJournal();
        markdown.append("\nRuntime journal: ")
                .append(journal.path("recorded").path("http").asLong())
                .append(" requests recorded, ")
                .append(journal.path("droppedEvents").asLong())
                .append(" events dropped.\n");
        writeFile(runtimeLabel() + ".md", markdown.toString());
        System.out.println(markdown);

        for (Phase phase : Phase.values()) {
            assertPhase(phase, reports.get(phase), perPhase);
            assertProfiles(phase, profiles.get(phase));
        }
        int sent = traffic.size() + Phase.values().length * perPhase;
        assertThat(journal.path("droppedEvents").asLong(-1))
                .as("the runtime journal drops nothing at default settings (docs/PLAN-v2.md §5.2)")
                .isZero();
        assertThat(journal.path("recorded").path("http").asLong())
                .as("the runtime journal recorded every request the scenario sent")
                .isGreaterThanOrEqualTo(sent);
    }

    /** The runtime journal's status, read through its public endpoint after the journal has caught up. */
    private JsonNode readJournal() throws InterruptedException {
        BootUiHttpProbe probe = new BootUiHttpProbe(baseUrl());
        JsonNode journal = null;
        for (int attempt = 0; attempt < MAX_FEED_READS; attempt++) {
            BootUiHttpProbe.Response response = probe.get("/bootui/api/activity/journal");
            assertThat(response.status()).as("runtime journal status").isEqualTo(200);
            journal = response.json();
            if (journal.path("queueDepth").asInt() == 0) {
                break;
            }
            Thread.sleep(200);
        }
        return journal;
    }

    private void assertProfiles(Phase phase, List<ProfileRead> read) {
        assertThat(read)
                .as(phase + ": the scenario reads the profile of requests that nested request-thread work")
                .isNotEmpty();
        for (ProfileRead profile : read) {
            assertThat(profile.available())
                    .as(phase + ": profile of " + profile.summary())
                    .isTrue();
            assertThat(profile.approximate())
                    .as(phase + ": request-thread work is correlated exactly, so the profile of " + profile.summary()
                            + " is not approximate")
                    .isFalse();
        }
    }

    private void assertPhase(Phase phase, Report report, int sent) {
        assertThat(report.requests())
                .as(phase + ": Live Activity must retain every request, or the measurement is incomplete")
                .isEqualTo(sent);
        assertThat(report.child("SQL").observed())
                .as(phase + ": the scenario routes must run SQL on a request-serving thread")
                .isPositive();
        assertThat(report.requestsSharingAnId())
                .as(phase + ": every request carries its own BootUI request id, so no two requests share an id")
                .isZero();
        if (tracing() == Tracing.OFF) {
            assertThat(report.requestsWithTraceId())
                    .as(phase + ": tracing is off, so no request carries a trace id")
                    .isZero();
        } else if (tracing() == Tracing.ON) {
            assertThat(report.requestsWithTraceId())
                    .as(phase + ": every request keeps its own trace id, however close identical requests are")
                    .isEqualTo(report.requests());
        }
        for (TypeCoverage coverage : report.children().values()) {
            assertThat(coverage.misattributed())
                    .as(phase + ": " + coverage.type()
                            + " nested under a request that was not running when it happened")
                    .isZero();
        }
        if (unownedThreadPattern() != null) {
            assertThat(report.child("SQL").unowned())
                    .as(phase + ": the raw-executor route must run SQL on the executor's threads")
                    .isPositive();
            assertThat(report.child("SQL").unownedNested())
                    .as(phase + ": work on an executor the application did not wrap is never guessed into a request")
                    .isZero();
        }
        for (Map.Entry<String, Double> floor : minimumNestedShares(phase).entrySet()) {
            TypeCoverage coverage = report.child(floor.getKey());
            if (coverage.observed() > 0) {
                assertThat(coverage.nestedShare())
                        .as(phase + ": " + floor.getKey() + " nested under its request")
                        .isGreaterThanOrEqualTo(floor.getValue());
            }
        }
    }

    private static long nextMillisecond() throws InterruptedException {
        long now = System.currentTimeMillis();
        while (System.currentTimeMillis() <= now) {
            Thread.sleep(1);
        }
        return System.currentTimeMillis();
    }

    private void sendSimultaneously(HttpClient client, Traffic route) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(route.concurrency());
        try {
            CountDownLatch ready = new CountDownLatch(route.concurrency());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < route.concurrency(); i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return send(client, route);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<Integer> result : results) {
                result.get(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private int send(HttpClient client, Traffic route) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + route.path()))
                .timeout(REQUEST_TIMEOUT)
                .GET();
        route.headers().forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private record FeedRead(List<Entry> entries, String body) {}

    private record ProfileRead(String summary, boolean available, boolean approximate) {}

    /**
     * Reads the profile of the first request of each route, in the window, that has request-thread work nested under
     * it. A route whose work all runs elsewhere, such as the raw-executor route, has none, so it is not read.
     */
    private List<ProfileRead> readProfiles(List<Entry> entries, long windowStart, long windowEnd) {
        Pattern requestThread = requestThreadPattern();
        Set<String> withRequestThreadWork = new HashSet<>();
        for (Entry entry : entries) {
            if (entry.parentId() != null
                    && entry.thread() != null
                    && requestThread.matcher(entry.thread()).matches()) {
                withRequestThreadWork.add(entry.parentId());
            }
        }
        Map<String, Entry> firstPerRoute = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if ("REQUEST".equals(entry.type())
                    && entry.timestamp() >= windowStart
                    && entry.timestamp() < windowEnd
                    && withRequestThreadWork.contains(entry.id())) {
                firstPerRoute.putIfAbsent(String.valueOf(entry.summary()), entry);
            }
        }
        BootUiHttpProbe probe = new BootUiHttpProbe(baseUrl());
        List<ProfileRead> read = new ArrayList<>();
        for (Entry request : firstPerRoute.values()) {
            BootUiHttpProbe.Response response = probe.get(
                    "/bootui/api/activity/request/" + URLEncoder.encode(request.id(), StandardCharsets.UTF_8));
            assertThat(response.status()).as("profile of " + request.summary()).isEqualTo(200);
            JsonNode profile = response.json();
            read.add(new ProfileRead(
                    request.summary(),
                    profile.path("available").asBoolean(false),
                    profile.path("approximate").asBoolean(true)
                            || profile.path("sqlCorrelationApproximate").asBoolean(true)));
        }
        return read;
    }

    /**
     * Reads the feed until it holds every scenario request. Reads are few and spaced out, because on some stacks
     * BootUI's own API calls still occupy HTTP exchange slots.
     */
    private FeedRead readFeed(long windowStart, int expectedRequests) throws InterruptedException {
        BootUiHttpProbe probe = new BootUiHttpProbe(baseUrl());
        FeedRead feed = new FeedRead(List.of(), "");
        for (int attempt = 0; attempt < MAX_FEED_READS; attempt++) {
            Thread.sleep(300L * (attempt + 1));
            BootUiHttpProbe.Response response = probe.get("/bootui/api/activity?since=" + (windowStart - 1));
            assertThat(response.status()).as("Live Activity feed").isEqualTo(200);
            List<Entry> entries = new ArrayList<>();
            for (JsonNode node : response.json().path("entries")) {
                entries.add(Entry.fromJson(node));
            }
            feed = new FeedRead(entries, response.body());
            long requests = entries.stream()
                    .filter(entry -> "REQUEST".equals(entry.type()) && entry.timestamp() >= windowStart)
                    .count();
            if (requests >= expectedRequests) {
                break;
            }
        }
        return feed;
    }

    private static void writeFile(String name, String content) throws IOException {
        Path directory = Path.of("target", "correlation-coverage");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(name), content, StandardCharsets.UTF_8);
    }
}
