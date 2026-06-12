package io.github.jdubois.bootui.autoconfigure.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.diagnostics.DiagnosticsCorrelator.ExceptionSignal;
import io.github.jdubois.bootui.autoconfigure.diagnostics.DiagnosticsCorrelator.HttpSignal;
import io.github.jdubois.bootui.autoconfigure.diagnostics.DiagnosticsCorrelator.Inputs;
import io.github.jdubois.bootui.autoconfigure.diagnostics.DiagnosticsCorrelator.SecuritySignal;
import io.github.jdubois.bootui.autoconfigure.diagnostics.DiagnosticsCorrelator.SqlSignal;
import io.github.jdubois.bootui.core.dto.DiagnosticsDashboardReport;
import io.github.jdubois.bootui.core.dto.DiagnosticsRequestDto;
import io.github.jdubois.bootui.core.dto.DiagnosticsSourcesDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiagnosticsCorrelatorTests {

    private static final DiagnosticsSourcesDto ALL_SOURCES =
            new DiagnosticsSourcesDto(true, true, true, true, true, false);

    private static Inputs inputs(
            List<HttpSignal> http,
            List<SqlSignal> sql,
            List<ExceptionSignal> exceptions,
            List<SecuritySignal> security) {
        return new Inputs(http, sql, exceptions, security, List.of(), ALL_SOURCES);
    }

    @Test
    void groupsSignalsSharingATraceIdIntoOneRequest() {
        Inputs inputs = inputs(
                List.of(new HttpSignal(1_000L, "trace-a", "GET", "/todos", 200, 12L, "alice")),
                List.of(new SqlSignal(1_010L, "trace-a", "http-1", "SELECT", "select * from todo", 5L, true, false)),
                List.of(new ExceptionSignal(
                        1_020L, "trace-a", "http-1", "java.lang.IllegalStateException", "boom", "GET", "/todos", true)),
                List.of());

        DiagnosticsDashboardReport report = DiagnosticsCorrelator.correlate(inputs, null, null, null);

        assertThat(report.available()).isTrue();
        assertThat(report.tracingActive()).isTrue();
        assertThat(report.requests()).hasSize(1);
        DiagnosticsRequestDto request = report.requests().get(0);
        assertThat(request.correlation()).isEqualTo("TRACE");
        assertThat(request.traceId()).isEqualTo("trace-a");
        assertThat(request.sqlCount()).isEqualTo(1);
        assertThat(request.exceptionCount()).isEqualTo(1);
        assertThat(request.hasError()).isTrue();
        assertThat(request.timeline()).extracting(e -> e.kind()).containsExactly("HTTP", "SQL", "EXCEPTION");
    }

    @Test
    void anchorsExceptionsOnHttpRequestsByPathWhenTracingIsOff() {
        Inputs inputs = inputs(
                List.of(new HttpSignal(2_000L, null, "POST", "/orders", 500, 30L, "bob")),
                List.of(),
                List.of(new ExceptionSignal(
                        2_010L, null, "http-2", "java.lang.RuntimeException", "kaput", "POST", "/orders", true)),
                List.of());

        DiagnosticsDashboardReport report = DiagnosticsCorrelator.correlate(inputs, null, null, null);

        assertThat(report.tracingActive()).isFalse();
        assertThat(report.requests()).hasSize(1);
        DiagnosticsRequestDto request = report.requests().get(0);
        assertThat(request.correlation()).isEqualTo("REQUEST");
        assertThat(request.exceptionCount()).isEqualTo(1);
        assertThat(request.path()).isEqualTo("/orders");
    }

    @Test
    void leavesUncorrelatableSecuritySignalsUnattributed() {
        Inputs inputs = inputs(
                List.of(),
                List.of(),
                List.of(),
                List.of(new SecuritySignal(3_000L, "carol", "AUTHENTICATION_FAILURE")));

        DiagnosticsDashboardReport report = DiagnosticsCorrelator.correlate(inputs, null, null, null);

        assertThat(report.requests()).isEmpty();
        assertThat(report.unattributed().securityCount()).isEqualTo(1);
        assertThat(report.unattributed().entries()).extracting(e -> e.kind()).contains("SECURITY");
    }

    @Test
    void filtersRequestsByQuery() {
        Inputs inputs = inputs(
                List.of(
                        new HttpSignal(4_000L, "trace-x", "GET", "/customers", 200, 8L, "dan"),
                        new HttpSignal(5_000L, "trace-y", "GET", "/products", 200, 9L, "dan")),
                List.of(
                        new SqlSignal(
                                4_010L, "trace-x", "http-3", "SELECT", "select * from customers", 4L, true, false),
                        new SqlSignal(
                                5_010L, "trace-y", "http-4", "SELECT", "select * from products", 4L, true, false)),
                List.of(),
                List.of());

        DiagnosticsDashboardReport report = DiagnosticsCorrelator.correlate(inputs, "customers", null, null);

        assertThat(report.requests()).hasSize(1);
        assertThat(report.requests().get(0).traceId()).isEqualTo("trace-x");
    }

    @Test
    void reportsUnavailableWhenNoSourcesAreActive() {
        Inputs inputs = new Inputs(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new DiagnosticsSourcesDto(false, false, false, false, false, false));

        DiagnosticsDashboardReport report = DiagnosticsCorrelator.correlate(inputs, null, null, null);

        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isNotBlank();
    }
}
