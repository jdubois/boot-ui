package io.github.jdubois.bootui.engine.security;

import io.github.jdubois.bootui.core.dto.AdvisorEvidenceDto;
import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import io.github.jdubois.bootui.core.dto.SecurityScanStatusDto;
import io.github.jdubois.bootui.core.dto.SecuritySeverityCountDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationCollector;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

/** Shared explicit-scan rule and report integration; never calls topology reports or application code. */
public final class WebSocketOriginRule {
    public static final String ID = "SEC-WS-001";
    public static final String NAME = "Review declared WebSocket wildcard origins";
    public static final String SEVERITY = "INFO";
    public static final String LIMITATION =
            "WebSocket origin policy coverage is incomplete: only supported, already-held native declarations are inspected; "
                    + "unexposed policies, custom interceptors, authentication and actual handshake acceptance are not assessed.";

    private WebSocketOriginRule() {}

    public static SecurityReport append(
            SecurityReport report, Supplier<WebSocketOriginObservation> supplier, AdvisorViolationCollector collector) {
        WebSocketOriginObservation observation;
        try {
            observation = supplier.get();
            if (observation == null) {
                observation = WebSocketOriginObservation.unavailable();
            }
        } catch (RuntimeException | LinkageError ex) {
            observation = new WebSocketOriginObservation(List.of(), false, true);
        }
        var endpoints = new LinkedHashMap<String, WebSocketOriginObservation.Endpoint>();
        boolean known = false;
        boolean complete = observation.complete() && !observation.failed();
        for (var endpoint : observation.endpoints()) {
            known |= endpoint.policyKnown();
            complete &= endpoint.policyKnown();
            if (endpoint.wildcard()) {
                endpoints.merge(endpoint.identity(), endpoint, (first, next) -> next.unrestricted() ? next : first);
            }
        }
        List<String> details = endpoints.values().stream()
                .map(endpoint -> endpoint.path()
                        + (endpoint.unrestricted()
                                ? ": declares the unrestricted '*' origin."
                                : ": declares a constrained wildcard origin (not necessarily every origin)."))
                .toList();
        List<SecurityRuleResultDto> results = new ArrayList<>(report.results());
        if (!details.isEmpty()) {
            collector.record(ID, details.size(), details, value -> value);
            results.add(result(
                    "VIOLATION", details.size(), details.stream().limit(10).toList()));
        }
        results.sort(Comparator.comparingInt((SecurityRuleResultDto result) -> SeverityOrder.rank(result.severity()))
                .thenComparing(Comparator.comparingInt(SecurityRuleResultDto::violationCount)
                        .reversed())
                .thenComparing(SecurityRuleResultDto::id));
        List<SecurityRuleResultDto> errors = new ArrayList<>(report.analysisErrors());
        if (observation.failed() && details.isEmpty()) {
            errors.add(result(
                    "ERROR", 0, List.of("Passive WebSocket origin collection failed; no exception text is exposed.")));
        }
        List<String> limitations = new ArrayList<>(report.evidence().limitations());
        if (!complete) {
            limitations.add(LIMITATION);
        }
        if (observation.failed()) {
            limitations.add(
                    "Some passive WebSocket origin declarations could not be read; known findings are retained.");
        }
        // A genuine declaration/finding supplies evidence, but vacuous absence and unknown policies do not.
        boolean usable = report.evidence().usable() || known || !details.isEmpty();
        AdvisorEvidenceDto evidence =
                new AdvisorEvidenceDto(usable, report.evidence().coverageComplete() && complete, limitations);
        int evaluated = report.rulesEvaluated() + ((known || !details.isEmpty()) ? 1 : 0);
        int count = (int) results.stream().filter(r -> !r.dismissed()).count();
        var old = report.scan();
        String status = old.status();
        if (!complete || ((known || !details.isEmpty()) && "DISABLED".equals(status))) {
            if (!"ERROR".equals(status)) {
                status = "PARTIAL";
            }
        }
        var scan = new SecurityScanStatusDto(
                old.analyzer(),
                status,
                old.message()
                        + (known || !details.isEmpty() ? " Declared WebSocket origins reviewed." : "")
                        + (!complete ? " WebSocket origin policy coverage is incomplete." : ""),
                old.scannedAt(),
                evaluated,
                old.filterChainsAnalyzed(),
                count);
        var severities = SeverityOrder.occurrenceCounts(
                        List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"),
                        results,
                        r -> !r.dismissed(),
                        SecurityRuleResultDto::severity,
                        SecurityRuleResultDto::violationCount)
                .entrySet()
                .stream()
                .map(e -> new SecuritySeverityCountDto(e.getKey(), e.getValue()))
                .toList();
        return new SecurityReport(
                report.localOnly(),
                report.disclaimer(),
                report.filterChains(),
                report.filterChainsAnalyzed(),
                evaluated,
                count,
                severities,
                scan,
                results,
                errors,
                evidence,
                report.violationDetails());
    }

    private static SecurityRuleResultDto result(String status, int count, List<String> details) {
        return new SecurityRuleResultDto(
                ID,
                NAME,
                "WebSocket",
                SEVERITY,
                "A declared wildcard is a review prompt, not a confirmed vulnerability. Cookie-authenticated sockets "
                        + "can be exposed to cross-site WebSocket hijacking; public or appropriately token-authenticated "
                        + "sockets may deliberately use broad origins. Custom interceptors, authentication and actual "
                        + "handshake acceptance are not assessed. HTTP CORS is not WebSocket policy evidence.",
                status,
                count,
                details,
                "Review each endpoint's declared handshake origin policy and authentication model; restrict origins "
                        + "where appropriate. No handshake is attempted.",
                "https://cheatsheetseries.owasp.org/cheatsheets/WebSocket_Security_Cheat_Sheet.html");
    }
}
