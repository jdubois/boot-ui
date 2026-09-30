package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.conformance.CorrelationCoverage.Entry;
import io.github.jdubois.bootui.conformance.CorrelationCoverage.Report;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CorrelationCoverageTest {

    private static final Pattern TOMCAT = Pattern.compile("http-nio-.+-exec-\\d+");

    @Test
    void countsNestedRequestThreadChildrenInsideTheWindowOnly() {
        List<Entry> entries = List.of(
                new Entry("warm-up", "REQUEST", 50, "t0", null, null, 200),
                new Entry("warm-up-sql", "SQL", 51, null, "http-nio-auto-1-exec-1", "warm-up", null),
                new Entry("r1", "REQUEST", 100, "t1", null, null, 200),
                new Entry("r2", "REQUEST", 100, null, null, null, 500),
                new Entry("s1", "SQL", 101, null, "http-nio-auto-1-exec-1", "r1", null),
                new Entry("s2", "SQL", 101, null, "http-nio-auto-1-exec-2", null, null),
                new Entry("s3", "SQL", 102, null, "scheduling-1", null, null),
                new Entry("s4", "SQL", 102, null, "http-nio-auto-1-exec-3", "warm-up", null),
                new Entry("later", "REQUEST", 200, "t2", null, null, 200),
                new Entry("later-sql", "SQL", 201, null, "http-nio-auto-1-exec-1", "later", null));

        Report report = CorrelationCoverage.measure(entries, 100, 200, TOMCAT);

        assertThat(report.requests()).isEqualTo(2);
        assertThat(report.requestsWithTraceId()).isEqualTo(1);
        assertThat(report.requestTraceIdShare()).isEqualTo(0.5);
        assertThat(report.failedRequests()).isEqualTo(1);
        assertThat(report.child("SQL").observed()).isEqualTo(3);
        assertThat(report.child("SQL").nested()).isEqualTo(1);
        assertThat(report.child("SQL").nestedShare()).isEqualTo(1.0 / 3);
        assertThat(report.child("SQL").otherThreads()).isEqualTo(1);
    }

    @Test
    void countsEveryRequestThatSharesItsIdWithAnother() {
        List<Entry> entries = List.of(
                new Entry("same", "REQUEST", 100, null, null, null, 200),
                new Entry("same", "REQUEST", 100, null, null, null, 200),
                new Entry("unique", "REQUEST", 101, null, null, null, 200));

        Report report = CorrelationCoverage.measure(entries, 0, Long.MAX_VALUE, TOMCAT);

        assertThat(report.requests()).isEqualTo(3);
        assertThat(report.requestsSharingAnId()).isEqualTo(2);
    }

    @Test
    void includesEntriesWithoutAThreadAndCountsThemSeparately() {
        List<Entry> entries = List.of(
                new Entry("r1", "REQUEST", 100, null, null, null, 200),
                new Entry("e1", "SECURITY", 101, null, null, "r1", null),
                new Entry("e2", "SECURITY", 101, null, "", null, null));

        Report report = CorrelationCoverage.measure(entries, 100, Long.MAX_VALUE, TOMCAT);

        assertThat(report.child("SECURITY").observed()).isEqualTo(2);
        assertThat(report.child("SECURITY").withoutThread()).isEqualTo(2);
        assertThat(report.child("SECURITY").nested()).isEqualTo(1);
    }

    @Test
    void nestingUnderANonRequestAnchorDoesNotCount() {
        List<Entry> entries = List.of(
                new Entry("job", "SCHEDULED", 100, null, "http-nio-auto-1-exec-1", null, null),
                new Entry("x1", "EXCEPTION", 101, null, "http-nio-auto-1-exec-1", "job", null));

        Report report = CorrelationCoverage.measure(entries, 100, Long.MAX_VALUE, TOMCAT);

        assertThat(report.requests()).isZero();
        assertThat(report.requestTraceIdShare()).isNull();
        assertThat(report.child("EXCEPTION").nested()).isZero();
    }

    @Test
    void unobservedTypesHaveNoShareAndRenderAsDashes() {
        Report report = CorrelationCoverage.measure(List.of(), 0, Long.MAX_VALUE, TOMCAT);

        assertThat(report.child("CACHE").nestedShare()).isNull();
        assertThat(report.children()).containsOnlyKeys(CorrelationCoverage.CHILD_TYPES);
        assertThat(report.toMarkdown())
                .contains("| Requests carrying a trace id | 0 | 0 | — | — | — | — |")
                .contains("| CACHE nested under its request | 0 | 0 | — | 0 | 0 | 0 |")
                .doesNotContain("unowned threads")
                .contains("Requests sharing an id with another request: 0. Failed (5xx) requests: 0.");
    }

    @Test
    void readsLiveActivityJsonIncludingNulls() throws Exception {
        String json = """
                {"id":"s1","type":"SQL","timestamp":101,"correlationId":null,
                 "thread":"http-nio-auto-1-exec-1","parentId":"r1","status":null,"summary":"select 1"}
                """;

        Entry entry = Entry.fromJson(new ObjectMapper().readTree(json));

        assertThat(entry)
                .isEqualTo(new Entry("s1", "SQL", 101, null, "http-nio-auto-1-exec-1", "r1", null, "select 1", null));
    }

    @Test
    void flagsAChildNestedUnderARequestWhoseIntervalDoesNotContainIt() {
        List<Entry> entries = List.of(
                request("r1", 100, 10),
                request("r2", 200, 10),
                child("inside", "SQL", 108, "http-nio-auto-1-exec-1", "r1"),
                child("rounded", "SQL", 114, "http-nio-auto-1-exec-1", "r1"),
                child("leaked", "SQL", 205, "http-nio-auto-1-exec-1", "r1"),
                child("acknowledged-later", "MESSAGING", 260, null, "r2"));

        Report report = CorrelationCoverage.measure(entries, 100, 300, TOMCAT);

        assertThat(report.child("SQL").nested()).isEqualTo(3);
        assertThat(report.child("SQL").misattributed()).isEqualTo(1);
        assertThat(report.child("MESSAGING").nested()).isEqualTo(1);
        assertThat(report.child("MESSAGING").misattributed()).isZero();
        assertThat(report.toMarkdown()).contains("| SQL nested under its request | 3 | 3 | 100.0 % | 0 | 0 | 1 |");
    }

    @Test
    void reportsUnownedThreadWorkApartFromRequestThreadWork() {
        Pattern raw = Pattern.compile("scenario-raw-\\d+");
        List<Entry> entries = List.of(
                request("r1", 100, 20),
                child("request-sql", "SQL", 101, "http-nio-auto-1-exec-1", "r1"),
                child("raw-sql", "SQL", 105, "scenario-raw-1", null),
                child("raw-guess", "SQL", 106, "scenario-raw-2", "r1"),
                child("scheduled-sql", "SQL", 107, "scheduling-1", null));

        Report report = CorrelationCoverage.measure(entries, 100, 200, 200, TOMCAT, raw);

        assertThat(report.child("SQL").observed()).isEqualTo(1);
        assertThat(report.child("SQL").nestedShare()).isEqualTo(1.0);
        assertThat(report.child("SQL").otherThreads()).isEqualTo(3);
        assertThat(report.child("SQL").unowned()).isEqualTo(2);
        assertThat(report.child("SQL").unownedNested()).isEqualTo(1);
        assertThat(report.toMarkdown()).contains("SQL on unowned threads: 2, of which nested under a request: 1.");
    }

    @Test
    void countsChildrenReportedAfterTheWindowClosedAndLeavesConsumedMessagesOut() {
        List<Entry> entries = List.of(
                request("r1", 100, 5),
                child("sent", "MESSAGING", 190, null, "r1", "→ orders [0]"),
                child("consumed", "MESSAGING", 150, null, null, "← orders [0]"),
                request("next-phase", 250, 5));

        Report report = CorrelationCoverage.measure(entries, 100, 180, 250, TOMCAT, null);

        assertThat(report.requests()).isEqualTo(1);
        assertThat(report.child("MESSAGING").observed()).isEqualTo(1);
        assertThat(report.child("MESSAGING").nestedShare()).isEqualTo(1.0);
    }

    private static Entry request(String id, long timestamp, long durationMs) {
        return new Entry(id, "REQUEST", timestamp, null, null, null, 200, "GET /", durationMs);
    }

    private static Entry child(String id, String type, long timestamp, String thread, String parentId) {
        return child(id, type, timestamp, thread, parentId, null);
    }

    private static Entry child(String id, String type, long timestamp, String thread, String parentId, String summary) {
        return new Entry(id, type, timestamp, null, thread, parentId, null, summary, null);
    }
}
