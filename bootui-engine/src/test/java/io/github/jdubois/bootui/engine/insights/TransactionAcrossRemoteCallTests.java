package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.ConnectionPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.TransactionPayload;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TransactionAcrossRemoteCallTests {

    private static final long MS = 1_000_000;

    private RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private int requests;

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void aTransactionOpenAcrossACallIsReportedWithItsHeldConnectionAndALabelledEstimate() {
        for (int i = 0; i < 4; i++) {
            request(
                    "POST",
                    "/api/orders",
                    transaction("OrderService.place", 0, 100),
                    new Child(JournalSource.CONNECTION, 99 * MS, new ConnectionPayload("db", 0, 3, 1 * MS)),
                    call("/items", 80, 50));
        }
        // Counterexample: the call starts after the transaction committed.
        request("POST", "/api/orders", transaction("OrderService.place", 0, 100), call("/items", 170, 50));
        // Insufficient: two transactions only.
        for (int i = 0; i < 2; i++) {
            request("PUT", "/api/stock", transaction("StockService.update", 0, 100), call("/sync", 80, 50));
        }

        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
        service.setPoolSizes(dataSource -> "db".equals(dataSource) ? 10 : null);
        RuntimeInsightsReportDto report = service.report();
        List<RuntimeObservationDto> found = report.observations().stream()
                .filter(observation -> observation.kind().equals(TransactionAcrossRemoteCall.KIND))
                .toList();

        assertThat(found).hasSize(2);
        assertThat(found.get(0).status()).isEqualTo("OBSERVED");
        assertThat(found.get(0).sentence())
                .isEqualTo("`POST /api/orders`: `OrderService.place` kept its transaction open across a call to `GET"
                        + " stock:8080/items` in 4 of 5 requests that both called out and opened a transaction; its"
                        + " calls took a median 50 ms, and its connection was held a median 99 ms. Estimate: at about"
                        + " 101 requests per second, this route alone would hold all 10 connections of `db` (pool"
                        + " size ÷ hold time).");
        assertThat(found.get(1).status()).isEqualTo("INSUFFICIENT");
        assertThat(found.get(1).subject()).isEqualTo("PUT /api/stock");
        assertThat(report.checks().stream()
                        .filter(check -> check.kind().equals(TransactionAcrossRemoteCall.KIND))
                        .findFirst()
                        .orElseThrow()
                        .status())
                .isEqualTo("EVALUATED");
        assertThat(new RuntimeInsightsService(journal, null, null, InsightsStack.QUARKUS, null)
                        .report().checks().stream()
                                .filter(check -> check.kind().equals(TransactionAcrossRemoteCall.KIND))
                                .findFirst()
                                .orElseThrow()
                                .status())
                .isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void enoughTransactionsWhoseCallsAreFastAreNotReportedAsNeedingMoreTraffic() {
        for (int i = 0; i < 4; i++) {
            request("PUT", "/api/stock", transaction("StockService.update", 0, 100), call("/sync", 80, 5));
        }

        RuntimeInsightsReportDto report =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null).report();

        assertThat(report.observations())
                .noneMatch(observation -> observation.kind().equals(TransactionAcrossRemoteCall.KIND));
        RuntimeInsightCheckDto check = check(report);
        assertThat(check.status()).isEqualTo("EVALUATED");
        assertThat(check.reason())
                .isEqualTo("1 transactional method kept a transaction open only across calls under 20 ms, so it is"
                        + " not reported.");
    }

    @Test
    void fewTransactionsWhoseCallsAreFastAreNotReportedAsNeedingMoreTraffic() {
        for (int i = 0; i < 2; i++) {
            request("PUT", "/api/stock", transaction("StockService.update", 0, 100), call("/sync", 80, 1));
        }

        RuntimeInsightsReportDto report =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null).report();

        assertThat(report.observations())
                .noneMatch(observation -> observation.kind().equals(TransactionAcrossRemoteCall.KIND));
        assertThat(check(report).reason()).startsWith("1 transactional method kept a transaction open only");
    }

    @Test
    void aSlowCallAmongFastOnesIsReportedEvenThoughTheMedianCallIsFast() {
        for (int i = 0; i < 4; i++) {
            Child[] children = new Child[11];
            children[0] = transaction("OrderService.place", 0, 600);
            for (int c = 0; c < 9; c++) {
                children[1 + c] = call("/items", 10 + 3 * c, 2);
            }
            children[10] = new Child(
                    JournalSource.REST_CLIENT,
                    500 * MS,
                    new RestClientPayload(
                            "POST", "payments:8080", "/charge", 200, "RestClient", false, null, 550 * MS));
            request("POST", "/api/orders", children);
        }

        RuntimeInsightsReportDto report =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null).report();
        List<RuntimeObservationDto> found = report.observations().stream()
                .filter(observation -> observation.kind().equals(TransactionAcrossRemoteCall.KIND))
                .toList();

        assertThat(found).singleElement().satisfies(observation -> {
            assertThat(observation.status()).isEqualTo("OBSERVED");
            assertThat(observation.sentence())
                    .contains("its calls took a median 2.0 ms, the slowest 500 ms (`POST payments:8080/charge`).");
        });
        assertThat(check(report).reason()).isNull();
    }

    @Test
    void aiOnlyCallsArePlacedAtTheirMonotonicStartEvenWhenTheyFinishAfterTheTransaction() {
        journalSources(Set.of(JournalSource.HTTP, JournalSource.TRANSACTION, JournalSource.AI));
        for (int i = 0; i < 3; i++) {
            request("POST", "/api/generate", transaction("Generator.run", 0, 100), ai(AiPayload.CHAT, 180, 100));
        }
        request("POST", "/api/generate", transaction("Generator.run", 0, 100), ai(AiPayload.CHAT, 180, 50));
        request("POST", "/api/generate", transaction("Generator.run", 0, 100), ai(AiPayload.CHAT, 160, 60));
        request("POST", "/api/generate", transaction("Generator.run", 0, 100), aiWithoutClock());
        request("POST", "/api/generate", transaction("Generator.run", 0, 100), aiWithoutDuration());

        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_WEBFLUX, null);
        RuntimeInsightsReportDto report = service.report();

        assertThat(report.observations())
                .filteredOn(observation -> observation.kind().equals(TransactionAcrossRemoteCall.KIND))
                .singleElement()
                .satisfies(observation -> {
                    assertThat(observation.status()).isEqualTo("OBSERVED");
                    assertThat(observation.affected()).isEqualTo(3);
                    assertThat(observation.sentence()).contains("`AI chat (gpt-4o)` in 3 of 5 requests");
                    assertThat(observation.evidenceRows()).isEqualTo(3);
                    assertThat(service.insight(observation.id()).rows().get(0).cells())
                            .contains("AI chat (gpt-4o)", "100");
                });
        assertThat(check(report).status()).isEqualTo("EVALUATED");
        assertThat(check(report).reason()).contains("Without the rest-client source");
    }

    @Test
    void aNestedRestClientCallIsNotCountedAgainButAnIndependentRestCallStillIs() {
        for (int i = 0; i < 3; i++) {
            request(
                    "POST",
                    "/api/generate",
                    transaction("Generator.run", 0, 150),
                    ai(AiPayload.CHAT, 130, 100),
                    call("/models", 90, 50));
        }
        request(
                "POST",
                "/api/generate",
                transaction("Generator.run", 0, 150),
                ai(AiPayload.CHAT, 130, 100),
                call("/independent", 140, 5));

        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
        RuntimeInsightsReportDto report = service.report();

        assertThat(report.observations())
                .filteredOn(observation -> observation.kind().equals(TransactionAcrossRemoteCall.KIND))
                .singleElement()
                .satisfies(observation -> {
                    assertThat(observation.status()).isEqualTo("OBSERVED");
                    assertThat(observation.affected()).isEqualTo(4);
                    assertThat(observation.sentence())
                            .contains("its calls took a median 100 ms")
                            .doesNotContain("stock:8080/models");
                    assertThat(observation.evidenceRows()).isEqualTo(5);
                    assertThat(service.insight(observation.id()).rows().toString())
                            .contains("stock:8080/independent")
                            .doesNotContain("stock:8080/models");
                });
    }

    @Test
    void restCallInsideTransactionStillCountsWhenEnclosingAiCallStartedBeforeIt() {
        for (int i = 0; i < 3; i++) {
            request(
                    "POST",
                    "/api/generate",
                    transaction("Generator.run", 50, 150),
                    ai(AiPayload.CHAT, 120, 100),
                    call("/models", 100, 50));
        }

        RuntimeInsightsReportDto report =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null).report();

        assertThat(report.observations())
                .filteredOn(observation -> observation.kind().equals(TransactionAcrossRemoteCall.KIND))
                .singleElement()
                .satisfies(observation -> {
                    assertThat(observation.sentence()).contains("`GET stock:8080/models` in 3 of 3 requests");
                    assertThat(observation.evidenceRows()).isEqualTo(3);
                });
    }

    @Test
    void neitherRemoteSourceAvailableMakesTheCheckNotApplicable() {
        journalSources(Set.of(JournalSource.HTTP, JournalSource.TRANSACTION));
        request("POST", "/api/generate", transaction("Generator.run", 0, 100));

        RuntimeInsightsReportDto report =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null).report();

        assertThat(check(report).status()).isEqualTo("NOT_APPLICABLE");
        assertThat(check(report).reason()).contains("Neither REST client nor AI calls");
    }

    @Test
    void restOnlySourceStillReportsCalls() {
        journalSources(Set.of(JournalSource.HTTP, JournalSource.TRANSACTION, JournalSource.REST_CLIENT));
        for (int i = 0; i < 3; i++) {
            request("POST", "/api/orders", transaction("OrderService.place", 0, 100), call("/items", 80, 50));
        }

        RuntimeInsightsReportDto report =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null).report();

        assertThat(report.observations())
                .filteredOn(observation -> observation.kind().equals(TransactionAcrossRemoteCall.KIND))
                .singleElement()
                .satisfies(observation -> assertThat(observation.status()).isEqualTo("OBSERVED"));
        assertThat(check(report).status()).isEqualTo("EVALUATED");
        assertThat(check(report).reason()).contains("Without the ai source");
    }

    @Test
    void hiddenRemotePanelsCannotSupplyOrSuppressEvidenceFromAVisibleSource() {
        for (int i = 0; i < 3; i++) {
            request(
                    "POST",
                    "/api/generate",
                    transaction("Generator.run", 0, 150),
                    ai(AiPayload.CHAT, 130, 100),
                    call("/models", 90, 50));
        }
        RuntimeInsightsReportDto withoutAi = new RuntimeInsightsService(
                        journal, null, panel -> !panel.equals(BootUiPanels.AI), InsightsStack.SPRING_MVC, null)
                .report();
        RuntimeInsightsReportDto withoutRest = new RuntimeInsightsService(
                        journal,
                        null,
                        panel -> !panel.equals(BootUiPanels.REST_CLIENT_TRACE),
                        InsightsStack.SPRING_MVC,
                        null)
                .report();

        assertThat(withoutAi.observations())
                .filteredOn(observation -> observation.kind().equals(TransactionAcrossRemoteCall.KIND))
                .singleElement()
                .satisfies(observation -> assertThat(observation.sentence())
                        .contains("GET stock:8080/models")
                        .doesNotContain("AI chat"));
        assertThat(withoutRest.observations())
                .filteredOn(observation -> observation.kind().equals(TransactionAcrossRemoteCall.KIND))
                .singleElement()
                .satisfies(observation ->
                        assertThat(observation.sentence()).contains("AI chat").doesNotContain("stock:8080/models"));
    }

    private void journalSources(Set<JournalSource> sources) {
        journal.close();
        journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, sources), RunIdentity.start());
    }

    private static RuntimeInsightCheckDto check(RuntimeInsightsReportDto report) {
        return report.checks().stream()
                .filter(check -> check.kind().equals(TransactionAcrossRemoteCall.KIND))
                .findFirst()
                .orElseThrow();
    }

    private static Child transaction(String method, long startMs, long endMs) {
        return new Child(
                JournalSource.TRANSACTION,
                (endMs - startMs) * MS,
                new TransactionPayload(method, false, false, false, startMs * MS));
    }

    private static Child call(String path, long completedMs, long durationMs) {
        return new Child(
                JournalSource.REST_CLIENT,
                durationMs * MS,
                new RestClientPayload("GET", "stock:8080", path, 200, "RestClient", false, null, completedMs * MS));
    }

    private static Child ai(String operation, long completedMs, long durationMs) {
        return new Child(
                JournalSource.AI,
                durationMs * MS,
                new AiPayload(operation, "openai", "gpt-4o", 10L, 5L, "stop", false, null, completedMs * MS));
    }

    private static Child aiWithoutClock() {
        return new Child(
                JournalSource.AI, 30 * MS, new AiPayload(AiPayload.CHAT, "openai", "gpt-4o", null, null, null, false));
    }

    private static Child aiWithoutDuration() {
        return new Child(
                JournalSource.AI,
                -1,
                new AiPayload(AiPayload.CHAT, "openai", "gpt-4o", null, null, null, false, null, 80 * MS));
    }

    private void request(String method, String template, Child... children) {
        String requestId = "r" + (++requests);
        CorrelationContext context = CorrelationContext.forRequest(requestId);
        for (Child child : children) {
            journal.offer(RuntimeEvent.of(
                    child.source(), 1_000, child.nanos(), context, "http-1", null, false, child.payload()));
        }
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000,
                200 * MS,
                context,
                "http-1",
                null,
                false,
                new HttpPayload(method, template, template, null, 200)));
        try {
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private record Child(JournalSource source, long nanos, RuntimeEventPayload payload) {}
}
