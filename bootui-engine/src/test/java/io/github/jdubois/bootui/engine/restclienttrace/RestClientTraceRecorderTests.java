package io.github.jdubois.bootui.engine.restclienttrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceGroupDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceReport;
import io.github.jdubois.bootui.core.dto.RestClientTraceStatsDto;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class RestClientTraceRecorderTests {

    private RestClientTraceRecorder recorder(boolean enabled, int maxEntries, long slowMillis) {
        return recorder(enabled, false, maxEntries, slowMillis);
    }

    private RestClientTraceRecorder recorder(boolean enabled, boolean captureHeaders, int maxEntries, long slowMillis) {
        return recorder(enabled, captureHeaders, false, maxEntries, slowMillis);
    }

    private RestClientTraceRecorder recorder(
            boolean enabled, boolean captureHeaders, boolean captureCallSite, int maxEntries, long slowMillis) {
        return new RestClientTraceRecorder(
                enabled, true, captureHeaders, captureCallSite, maxEntries, slowMillis, 2000, 200, 5);
    }

    private void record(RestClientTraceRecorder recorder, String path) {
        recorder.record("GET", path, "api.example.com", path, 200, 1, true, null, "RestClient", Map.of(), "main");
    }

    @Test
    void recordsNothingWhenDisabled() {
        RestClientTraceRecorder recorder = recorder(false, 10, 100);
        record(recorder, "/orders");
        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.totalCaptured()).isZero();
    }

    @Test
    void recordsNothingWhenPaused() {
        RestClientTraceRecorder recorder = recorder(true, 10, 100);
        recorder.setRecording(false);
        record(recorder, "/orders");
        assertThat(recorder.recent()).isEmpty();

        recorder.setRecording(true);
        record(recorder, "/orders2");
        assertThat(recorder.recent()).hasSize(1);
    }

    @Test
    void suspendForIdleClearsAndStopsRecordingUntilResumed() {
        RestClientTraceRecorder recorder = recorder(true, 10, 100);
        record(recorder, "/orders");
        assertThat(recorder.recent()).hasSize(1);

        recorder.suspendForIdle();
        assertThat(recorder.recent()).isEmpty();
        record(recorder, "/orders2");
        assertThat(recorder.recent()).isEmpty();

        recorder.resumeFromIdle();
        record(recorder, "/orders3");
        assertThat(recorder.recent()).hasSize(1);
    }

    @Test
    void resumeFromIdleDoesNotOverrideUserPause() {
        RestClientTraceRecorder recorder = recorder(true, 10, 100);
        recorder.setRecording(false);

        recorder.suspendForIdle();
        recorder.resumeFromIdle();

        record(recorder, "/orders");
        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void evictsOldestBeyondCapacityAndCountsEvictions() {
        RestClientTraceRecorder recorder = recorder(true, 2, 100);
        record(recorder, "/first");
        record(recorder, "/second");
        record(recorder, "/third");

        assertThat(recorder.recent())
                .extracting(RestClientTraceRecorder.CapturedCall::path)
                .containsExactly("/third", "/second");
        assertThat(recorder.totalCaptured()).isEqualTo(3);
        assertThat(recorder.evicted()).isEqualTo(1);
    }

    @Test
    void dropsHeadersWhenCaptureDisabled() {
        RestClientTraceRecorder recorder = recorder(true, false, 10, 100);
        recorder.record(
                "GET",
                "/orders",
                "api.example.com",
                "/orders",
                200,
                1,
                true,
                null,
                "RestClient",
                Map.of("Authorization", "Bearer abc"),
                "main");
        assertThat(recorder.recent().get(0).requestHeaders()).isEmpty();
    }

    @Test
    void keepsHeadersAndThreadWhenCaptureEnabled() {
        RestClientTraceRecorder recorder = recorder(true, true, 10, 100);
        recorder.record(
                "GET",
                "/orders",
                "api.example.com",
                "/orders",
                200,
                1,
                true,
                null,
                "RestClient",
                Map.of("X-Trace", "abc"),
                "worker-1");
        assertThat(recorder.recent().get(0).requestHeaders()).containsEntry("X-Trace", "abc");
        assertThat(recorder.recent().get(0).thread()).isEqualTo("worker-1");
    }

    @Test
    void storesHeaderAndQueryParameterValuesRawAndUnmaskedInTheBuffer() {
        RestClientTraceRecorder recorder = recorder(true, true, 10, 100);
        recorder.record(
                "GET",
                "https://api.example.com/orders?apiKey=verysecretvalue&page=2",
                "api.example.com",
                "/orders",
                200,
                1,
                true,
                null,
                "RestClient",
                Map.of("Authorization", "Bearer verysecrettoken", "X-Trace", "abc"),
                "main");

        RestClientTraceRecorder.CapturedCall entry = recorder.recent().get(0);
        assertThat(entry.uri()).contains("apiKey=verysecretvalue");
        assertThat(entry.requestHeaders()).containsEntry("Authorization", "Bearer verysecrettoken");
    }

    @Test
    void maskedReportHidesSecretHeaderAndQueryParameterValuesByNameButKeepsNonSecretValues() {
        RestClientTraceRecorder recorder = recorder(true, true, 10, 100);
        recorder.record(
                "GET",
                "https://api.example.com/orders?apiKey=verysecretvalue&page=2",
                "api.example.com",
                "/orders",
                200,
                1,
                true,
                null,
                "RestClient",
                Map.of("Authorization", "Bearer verysecrettoken", "X-Trace", "abc"),
                "main");

        RestClientTraceEntryDto entry =
                recorder.report(true, ValueExposure.MASKED).entries().get(0);
        assertThat(entry.uri()).contains("apiKey=" + SecretMasker.MASKED_VALUE);
        assertThat(entry.uri()).contains("page=2");
        assertThat(entry.uri()).doesNotContain("verysecretvalue");
        assertThat(entry.requestHeaders().get("Authorization")).isEqualTo(SecretMasker.MASKED_VALUE);
        assertThat(entry.requestHeaders().get("X-Trace")).isEqualTo("abc");
    }

    @Test
    void maskSecretsFalseShowsRawValuesEvenForSecretLookingNames() {
        RestClientTraceRecorder recorder = recorder(true, true, 10, 100);
        recorder.record(
                "GET",
                "https://api.example.com/orders?apiKey=verysecretvalue",
                "api.example.com",
                "/orders",
                200,
                1,
                true,
                null,
                "RestClient",
                Map.of("Authorization", "Bearer verysecrettoken"),
                "main");

        RestClientTraceEntryDto entry =
                recorder.report(false, ValueExposure.MASKED).entries().get(0);
        assertThat(entry.uri()).contains("apiKey=verysecretvalue");
        assertThat(entry.requestHeaders().get("Authorization")).isEqualTo("Bearer verysecrettoken");
    }

    @Test
    void fullExposureShowsSecretValuesEvenWhenMaskSecretsIsTrue() {
        RestClientTraceRecorder recorder = recorder(true, true, 10, 100);
        recorder.record(
                "GET",
                "https://api.example.com/orders?apiKey=verysecretvalue",
                "api.example.com",
                "/orders",
                200,
                1,
                true,
                null,
                "RestClient",
                Map.of("Authorization", "Bearer verysecrettoken"),
                "main");

        RestClientTraceEntryDto entry =
                recorder.report(true, ValueExposure.FULL).entries().get(0);
        assertThat(entry.uri()).contains("apiKey=verysecretvalue");
        assertThat(entry.requestHeaders().get("Authorization")).isEqualTo("Bearer verysecrettoken");
    }

    @Test
    void metadataOnlyExposureHidesAllValuesRegardlessOfSecrecy() {
        RestClientTraceRecorder recorder = recorder(true, true, 10, 100);
        recorder.record(
                "GET",
                "https://api.example.com/orders?apiKey=verysecretvalue&page=2",
                "api.example.com",
                "/orders",
                200,
                1,
                true,
                null,
                "RestClient",
                Map.of("Authorization", "Bearer verysecrettoken", "X-Trace", "abc"),
                "main");

        RestClientTraceEntryDto entry =
                recorder.report(true, ValueExposure.METADATA_ONLY).entries().get(0);
        assertThat(entry.uri()).isEqualTo("https://api.example.com/orders");
        assertThat(entry.requestHeaders().get("Authorization")).isEmpty();
        assertThat(entry.requestHeaders().get("X-Trace")).isEmpty();
    }

    @Test
    void leavesUriUntouchedWhenNoQueryString() {
        RestClientTraceRecorder recorder = recorder(true, 10, 100);
        recorder.record(
                "GET",
                "https://api.example.com/orders",
                "api.example.com",
                "/orders",
                200,
                1,
                true,
                null,
                "RestClient",
                Map.of(),
                "main");

        assertThat(recorder.recent().get(0).uri()).isEqualTo("https://api.example.com/orders");
    }

    @Test
    void flagsSlowCallsByThreshold() {
        RestClientTraceRecorder recorder = recorder(true, 10, 100);
        assertThat(recorder.isSlow(150)).isTrue();
        assertThat(recorder.isSlow(50)).isFalse();
    }

    @Test
    void slowFlaggingDisabledWhenThresholdZero() {
        RestClientTraceRecorder recorder = recorder(true, 10, 0);
        assertThat(recorder.isSlow(5000)).isFalse();
    }

    @Test
    void tracksInstrumentedClientTypes() {
        RestClientTraceRecorder recorder = recorder(true, 10, 100);
        assertThat(recorder.hasInstrumentedClient()).isFalse();
        recorder.registerClientCustomization("RestClient");
        recorder.registerClientCustomization("RestClient");
        recorder.registerClientCustomization(" ");
        assertThat(recorder.hasInstrumentedClient()).isTrue();
        assertThat(recorder.clientTypes()).containsExactly("RestClient");
    }

    @Test
    void computesAggregateStats() {
        RestClientTraceRecorder recorder = recorder(true, 10, 100);
        recorder.record("GET", "/a", "api.example.com", "/a", 200, 10, true, null, "RestClient", Map.of(), "main");
        recorder.record("POST", "/b", "api.example.com", "/b", 201, 200, true, null, "RestClient", Map.of(), "main");
        recorder.record(
                "PUT", "/c", "api.example.com", "/c", null, 50, false, "boom", "RestTemplate", Map.of(), "main");
        recorder.record("DELETE", "/d", "api.example.com", "/d", 404, 5, true, null, "WebClient", Map.of(), "main");

        RestClientTraceStatsDto stats = recorder.stats();
        assertThat(stats.totalCalls()).isEqualTo(4);
        assertThat(stats.totalDurationMillis()).isEqualTo(265);
        assertThat(stats.maxDurationMillis()).isEqualTo(200);
        assertThat(stats.slowCalls()).isEqualTo(1);
        assertThat(stats.failedCalls()).isEqualTo(1);
        assertThat(stats.errorStatusCalls()).isEqualTo(1);
        assertThat(stats.getCount()).isEqualTo(1);
        assertThat(stats.postCount()).isEqualTo(1);
        assertThat(stats.putCount()).isEqualTo(1);
        assertThat(stats.deleteCount()).isEqualTo(1);
        assertThat(stats.otherCount()).isZero();
    }

    @Test
    void groupsRepeatedCallsViaTopCalls() {
        RestClientTraceRecorder recorder = recorder(true, 100, 100);
        for (int i = 0; i < 6; i++) {
            recorder.record(
                    "GET",
                    "/orders/" + i,
                    "api.example.com",
                    "/orders/" + i,
                    200,
                    1,
                    true,
                    null,
                    "RestClient",
                    Map.of(),
                    "main");
        }
        recorder.record(
                "GET", "/health", "api.example.com", "/health", 200, 1, true, null, "RestClient", Map.of(), "main");

        List<RestClientTraceGroupDto> groups = recorder.topCalls(true, ValueExposure.MASKED);
        assertThat(groups).hasSize(2);
        RestClientTraceGroupDto top = groups.get(0);
        assertThat(top.path()).isEqualTo("/orders/{id}");
        assertThat(top.executions()).isEqualTo(6);
        assertThat(top.chatty()).isTrue();
    }

    @Test
    void omitsCallSiteWhenCaptureDisabled() {
        RestClientTraceRecorder recorder = recorder(true, false, false, 10, 100);
        record(recorder, "/orders");
        assertThat(recorder.recent().get(0).callSite()).isNull();
    }

    @Test
    void neverThrowsAndStillRecordsWhenCallSiteCaptureIsEnabled() {
        RestClientTraceRecorder recorder = recorder(true, false, true, 10, 100);
        record(recorder, "/orders");
        // Best-effort: within this test suite's own call stack every frame belongs to BootUI, the JDK,
        // JUnit, or the build tool (see StackFramePrefixes), so no application frame is ever found here and
        // the call site is null. The important guarantee under test is that enabling capture never throws
        // or disrupts recording; the frame-selection algorithm itself (with a synthetic application frame)
        // is covered in isolation by the selectCallSite* tests below.
        assertThat(recorder.recent()).hasSize(1);
        assertThat(recorder.recent().get(0).callSite()).isNull();
    }

    @Test
    void selectCallSiteFindsFirstApplicationFrame() {
        StackWalker.StackFrame jdk = frame("java.net.http.HttpClient", "send", "HttpClient.java", 10);
        StackWalker.StackFrame app = frame("com.example.app.OrderClient", "findAll", "OrderClient.java", 42);

        String result = RestClientTraceRecorder.selectCallSite(Stream.of(jdk, app));

        assertThat(result).isEqualTo("com.example.app.OrderClient.findAll(OrderClient.java:42)");
    }

    @Test
    void selectCallSiteSkipsFrameworkAndBootUiFramesToFindTheApplicationFrame() {
        StackWalker.StackFrame bootui = frame(
                "io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder",
                "record",
                "RestClientTraceRecorder.java",
                200);
        StackWalker.StackFrame spring =
                frame("org.springframework.web.client.RestClient", "execute", "RestClient.java", 5);
        StackWalker.StackFrame app = frame("com.example.app.OrderClient", "findAll", "OrderClient.java", 42);

        String result = RestClientTraceRecorder.selectCallSite(Stream.of(bootui, spring, app));

        assertThat(result).isEqualTo("com.example.app.OrderClient.findAll(OrderClient.java:42)");
    }

    @Test
    void selectCallSiteReturnsNullWhenEveryFrameIsFrameworkOrBootUiCode() {
        StackWalker.StackFrame bootui = frame(
                "io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder",
                "record",
                "RestClientTraceRecorder.java",
                200);
        StackWalker.StackFrame jdk = frame("java.net.http.HttpClient", "send", "HttpClient.java", 10);

        assertThat(RestClientTraceRecorder.selectCallSite(Stream.of(bootui, jdk)))
                .isNull();
    }

    @Test
    void selectCallSiteGivesUpBeyondTheFrameLimit() {
        StackWalker.StackFrame framework = frame("java.net.http.HttpClient", "send", "HttpClient.java", 10);
        StackWalker.StackFrame app = frame("com.example.app.OrderClient", "findAll", "OrderClient.java", 42);
        Stream<StackWalker.StackFrame> frames =
                Stream.concat(Stream.generate(() -> framework).limit(130), Stream.of(app));

        assertThat(RestClientTraceRecorder.selectCallSite(frames)).isNull();
    }

    private static StackWalker.StackFrame frame(String className, String methodName, String fileName, int lineNumber) {
        StackWalker.StackFrame frame = mock(StackWalker.StackFrame.class);
        when(frame.getClassName()).thenReturn(className);
        when(frame.getMethodName()).thenReturn(methodName);
        when(frame.getFileName()).thenReturn(fileName);
        when(frame.getLineNumber()).thenReturn(lineNumber);
        return frame;
    }

    @Test
    void clearEmptiesBufferButKeepsTotalCaptured() {
        RestClientTraceRecorder recorder = recorder(true, 10, 100);
        record(recorder, "/orders");
        recorder.clear();
        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.totalCaptured()).isEqualTo(1);
    }

    @Test
    void notifiesSubscribersOnRecordClearAndRecordingChange() {
        RestClientTraceRecorder recorder = recorder(true, 10, 100);
        java.util.concurrent.atomic.AtomicInteger notifications = new java.util.concurrent.atomic.AtomicInteger();
        Runnable handle = recorder.subscribe(notifications::incrementAndGet);

        record(recorder, "/orders");
        assertThat(notifications.get()).isEqualTo(1);

        recorder.clear();
        assertThat(notifications.get()).isEqualTo(2);

        recorder.setRecording(false);
        assertThat(notifications.get()).isEqualTo(3);
        // No change in value -> no extra notification.
        recorder.setRecording(false);
        assertThat(notifications.get()).isEqualTo(3);

        handle.run();
        recorder.setRecording(true);
        record(recorder, "/orders");
        assertThat(notifications.get()).isEqualTo(3);
    }

    @Test
    void stampsTraceIdFromConfiguredProvider() {
        RestClientTraceRecorder recorder = recorder(true, 10, 100);
        recorder.setTraceIdProvider(() -> "trace-x");
        record(recorder, "/orders");
        assertThat(recorder.recent().get(0).traceId()).isEqualTo("trace-x");
    }

    @Test
    void usesNoTraceIdByDefault() {
        RestClientTraceRecorder recorder = recorder(true, 10, 100);
        record(recorder, "/orders");
        assertThat(recorder.recent().get(0).traceId()).isNull();
    }

    @Test
    void treatsBlankProviderTraceIdAsNone() {
        RestClientTraceRecorder recorder = recorder(true, 10, 100);
        recorder.setTraceIdProvider(() -> "   ");
        record(recorder, "/orders");
        assertThat(recorder.recent().get(0).traceId()).isNull();
    }

    @Test
    void nullProviderRestoresDefaultAndNeverThrows() {
        RestClientTraceRecorder recorder = recorder(true, 10, 100);
        recorder.setTraceIdProvider(() -> {
            throw new IllegalStateException("tracer broke");
        });
        recorder.setTraceIdProvider(null);
        record(recorder, "/orders");
        assertThat(recorder.recent().get(0).traceId()).isNull();
    }

    @Test
    void guardsAgainstThrowingProvider() {
        RestClientTraceRecorder recorder = recorder(true, 10, 100);
        recorder.setTraceIdProvider(() -> {
            throw new IllegalStateException("tracer broke");
        });
        record(recorder, "/orders");
        assertThat(recorder.recent()).hasSize(1);
        assertThat(recorder.recent().get(0).traceId()).isNull();
    }

    @Test
    void reportIncludesStatsTopCallsAndWarningWhenEntriesWereEvicted() {
        RestClientTraceRecorder recorder = recorder(true, 1, 100);
        record(recorder, "/orders");
        record(recorder, "/items");

        RestClientTraceReport report = recorder.report(true, ValueExposure.MASKED);
        assertThat(report.available()).isTrue();
        assertThat(report.entries()).hasSize(1);
        assertThat(report.stats().totalCalls()).isEqualTo(1);
        assertThat(report.warnings()).anyMatch(w -> w.contains("dropped"));
    }

    @Test
    void reportWarnsWhenPausedAndWhenCapturingHeaders() {
        RestClientTraceRecorder recorder = recorder(true, true, 10, 100);
        recorder.setRecording(false);

        RestClientTraceReport report = recorder.report(true, ValueExposure.MASKED);
        assertThat(report.warnings()).anyMatch(w -> w.contains("paused"));
        assertThat(report.warnings()).anyMatch(w -> w.contains("headers are captured"));
    }
}
