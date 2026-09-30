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
                .contains("| Requests carrying a trace id | 0 | 0 | — | — | — |")
                .contains("| CACHE nested under its request | 0 | 0 | — | 0 | 0 |")
                .contains("Requests sharing an id with another request: 0. Failed (5xx) requests: 0.");
    }

    @Test
    void readsLiveActivityJsonIncludingNulls() throws Exception {
        String json = """
                {"id":"s1","type":"SQL","timestamp":101,"correlationId":null,
                 "thread":"http-nio-auto-1-exec-1","parentId":"r1","status":null,"summary":"select 1"}
                """;

        Entry entry = Entry.fromJson(new ObjectMapper().readTree(json));

        assertThat(entry).isEqualTo(new Entry("s1", "SQL", 101, null, "http-nio-auto-1-exec-1", "r1", null));
    }
}
