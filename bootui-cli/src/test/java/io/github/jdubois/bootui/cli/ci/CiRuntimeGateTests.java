package io.github.jdubois.bootui.cli.ci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.client.JsonValue;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CiRuntimeGateTests {

    private static final String KIND = "anonymous-data-reach";

    @Test
    void anObservedRowIsAViolationWithItsAffectedCount() {
        CiRuntimeGate.Result result = CiRuntimeGate.evaluate(KIND, report(List.of(), "OBSERVED", 3, 0, false));

        assertThat(result).isEqualTo(new CiRuntimeGate.Result(KIND, "VIOLATION", "-", 3));
    }

    @Test
    void anEvaluatedCheckWithoutARowPasses() {
        CiRuntimeGate.Result result =
                CiRuntimeGate.evaluate(KIND, report(List.of("other-kind: NOT_APPLICABLE"), null, 0, 0, false));

        assertThat(result).isEqualTo(new CiRuntimeGate.Result(KIND, "PASS", "-", 0));
    }

    @Test
    void aCheckThatDidNotRunOrRanPartlyIsNotAssessed() {
        assertThat(CiRuntimeGate.evaluate(KIND, report(List.of(KIND + ": NOT_APPLICABLE, no SQL"), null, 0, 0, false)))
                .isEqualTo(new CiRuntimeGate.Result(KIND, "NOT_ASSESSED", "CHECK_NOT_RUN", 0));
        assertThat(CiRuntimeGate.evaluate(KIND, report(List.of(KIND + ": EVALUATED, partly: x"), null, 0, 0, false)))
                .isEqualTo(new CiRuntimeGate.Result(KIND, "NOT_ASSESSED", "CHECK_NOT_RUN", 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSUFFICIENT", "PARTIAL"})
    void incompleteRowsAreNotAssessed(String status) {
        assertThat(CiRuntimeGate.evaluate(KIND, report(List.of(), status, 0, 0, false)))
                .isEqualTo(new CiRuntimeGate.Result(KIND, "NOT_ASSESSED", status, 0));
    }

    @Test
    void omittedRowsOrAPausedCaptureCannotPass() {
        assertThat(CiRuntimeGate.evaluate(KIND, report(List.of(), null, 0, 1, false))
                        .reason())
                .isEqualTo("OMITTED");
        assertThat(CiRuntimeGate.evaluate(KIND, report(List.of(), null, 0, 0, true))
                        .reason())
                .isEqualTo("PARTIAL_CAPTURE");
    }

    @Test
    void anUnavailableJournalIsNotAssessed() {
        JsonValue unavailable = JsonValue.parseStrict("{\"available\":false,\"unavailableReason\":\"private\"}");

        assertThat(CiRuntimeGate.evaluate(KIND, unavailable))
                .isEqualTo(new CiRuntimeGate.Result(KIND, "NOT_ASSESSED", "UNAVAILABLE", 0));
    }

    @Test
    void aProvenObservationOutranksAGapInTheSameCheck() {
        assertThat(CiRuntimeGate.evaluate(
                                KIND, report(List.of(KIND + ": EVALUATED, partly: x"), "OBSERVED", 2, 4, true))
                        .result())
                .isEqualTo("VIOLATION");
    }

    @Test
    void rowsOfAnotherKindAreIgnored() {
        JsonValue other = report(List.of(), "OBSERVED", 5, 0, false);
        String text = io.github.jdubois.bootui.client.JsonWriter.pretty(other).replace(KIND, "repeated-selects");

        assertThat(CiRuntimeGate.evaluate(KIND, JsonValue.parseStrict(text)).result())
                .isEqualTo("PASS");
    }

    @Test
    void aMalformedReportIsRejected() {
        assertThatThrownBy(() -> CiRuntimeGate.evaluate(KIND, JsonValue.parseStrict("{\"available\":true}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CiRuntimeGate.evaluate(KIND, report(List.of(), "SOMETHING", 1, 0, false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyNamesOnlyGateableKindsAndRequiresInsights() {
        CiPolicy policy =
                CiPolicy.parse(policy(true, "[\"anonymous-success-on-restricted-route\",\"" + KIND + "\"]"), "s");

        assertThat(policy.failOnObservations()).containsExactly("anonymous-success-on-restricted-route", KIND);
        assertThat(CiPolicy.parse(policy(true, null), "s").failOnObservations()).isEmpty();
        assertThatThrownBy(() -> CiPolicy.parse(policy(false, "[\"" + KIND + "\"]"), "s"))
                .hasMessageContaining("failOnObservations requires runtimeContext.includeInsights");
        assertThatThrownBy(() -> CiPolicy.parse(policy(true, "[\"repeated-selects\"]"), "s"))
                .hasMessageContaining("cannot gate on repeated-selects");
        assertThatThrownBy(() -> CiPolicy.parse(policy(true, "[\"" + KIND + "\",\"" + KIND + "\"]"), "s"))
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> CiPolicy.parse(policy(true, "[]"), "s")).hasMessageContaining("at least one");
        assertThatThrownBy(() -> CiPolicy.parse(policy(true, "\"" + KIND + "\""), "s"))
                .hasMessageContaining("array");
    }

    private static String policy(boolean includeInsights, String failOnObservations) {
        return """
                {"schemaVersion":1,"advisors":{"architecture":{"required":true,"failOn":"high"}},
                 "runtimeContext":{"includeInsights":%s%s}}
                """.formatted(
                includeInsights, failOnObservations == null ? "" : ",\"failOnObservations\":" + failOnObservations);
    }

    private static JsonValue report(
            List<String> checksNotRun, String status, long affected, int omitted, boolean paused) {
        StringBuilder notRun = new StringBuilder();
        for (String entry : checksNotRun) {
            notRun.append(notRun.isEmpty() ? "" : ",").append('"').append(entry).append('"');
        }
        String observations = status == null ? "" : """
                {"id":"private-id","kind":"%s","status":"%s","subject":"private-route","sentence":"private sentence",
                 "eligible":4,"affected":%d,"listed":false}
                """.formatted(KIND, status, affected);
        String lowFootprint = paused ? ",\"lowFootprint\":{\"active\":true,\"paused\":true}" : "";
        return JsonValue.parseStrict("""
                {"available":true,"unavailableReason":null,"query":"%s","requests":3,"coverage":[],
                 "checksNotRun":[%s],"observations":[%s],"omitted":%d,"limitations":["private limitation"]%s}
                """.formatted(KIND, notRun, observations, omitted, lowFootprint));
    }
}
