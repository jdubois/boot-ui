package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.CodeInventoryMethodDto;
import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.inventory.CodeChanges;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService.ChangedClass;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService.ChangedCode;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ChangedCodeNotExecutedTests {

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private final AtomicReference<ChangedCode> code = new AtomicReference<>();
    private boolean panelEnabled = true;

    @AfterEach
    void close() {
        journal.close();
    }

    private RuntimeInsightsService service() {
        RuntimeInsightsService service = new RuntimeInsightsService(
                journal, null, panel -> panelEnabled || !panel.equals("code-inventory"), null, List::of);
        service.setCodeInventory(code::get);
        return service;
    }

    private static CodeInventoryMethodDto method(String name, String change, String status) {
        return new CodeInventoryMethodDto(
                "shop.OrderService#" + name + "()V",
                "shop",
                "shop.OrderService",
                name,
                "()V",
                status,
                CodeInventoryService.NOT_TRACKED.equals(status) ? "transform failed" : null,
                change,
                CodeInventoryService.EXECUTED.equals(status) ? "00000000000000ab" : null,
                CodeInventoryService.EXECUTED.equals(status) ? "GET /orders" : null,
                null);
    }

    private static ChangedCode changes(CodeInventoryMethodDto... methods) {
        return new ChangedCode(
                null,
                true,
                null,
                List.of(new ChangedClass("shop.OrderService", List.of(methods), List.of("GET /orders"))),
                methods.length,
                "COMPLETE",
                null);
    }

    private static RuntimeInsightCheckDto check(RuntimeInsightsReportDto report) {
        return report.checks().stream()
                .filter(check -> check.kind().equals(ChangedCodeNotExecuted.KIND))
                .findFirst()
                .orElseThrow();
    }

    private static List<RuntimeObservationDto> observations(RuntimeInsightsReportDto report) {
        return report.observations().stream()
                .filter(observation -> observation.kind().equals(ChangedCodeNotExecuted.KIND))
                .toList();
    }

    @Test
    void aChangedMethodNotExecutedIsReportedPerClassWithTheRoutesOfItsOtherMethods() {
        code.set(changes(
                method("pay", CodeChanges.CHANGED, CodeInventoryService.NEVER_EXECUTED),
                method("refund", CodeChanges.ADDED, CodeInventoryService.NEVER_EXECUTED),
                method("list", CodeChanges.CHANGED, CodeInventoryService.EXECUTED)));
        RuntimeInsightsService service = service();

        RuntimeInsightsReportDto report = service.report();

        assertThat(check(report).status()).isEqualTo("EVALUATED");
        assertThat(observations(report)).singleElement().satisfies(observation -> {
            assertThat(observation.subject()).isEqualTo("shop.OrderService");
            assertThat(observation.sentence())
                    .isEqualTo("Your change has not run yet: 2 changed methods of `OrderService` were not executed in"
                            + " this run.");
            assertThat(observation.eligible()).isEqualTo(3);
            assertThat(observation.affected()).isEqualTo(2);
            assertThat(observation.whatToCheck().get(0))
                    .as("without declared routes, no route is suggested, not another method's")
                    .startsWith("No route is known to be mapped to them")
                    .doesNotContain("GET /orders");
            assertThat(observation.limitations())
                    .contains("Routes that executed this class's methods in this run: GET /orders.");
        });
        RuntimeObservationDetailDto detail =
                service.insight(observations(report).get(0).id());
        assertThat(detail.rows())
                .extracting(row -> row.cells().get(0))
                .containsExactly("OrderService.pay()V", "OrderService.refund()V");
    }

    /**
     * The route to send is the one mapped to the changed method, by its own HTTP method and path (M4-20's adjudication
     * follow-up 4, Super Heroes' {@code @DELETE deleteAllVillains}): never another route of its class, such as the
     * {@code GET} on the same path that ran the class's other methods.
     */
    @Test
    void theRouteMappedToTheChangedMethodIsSuggestedNeverAnotherVerbOnTheClass() {
        code.set(changes(
                method("pay", CodeChanges.CHANGED, CodeInventoryService.NEVER_EXECUTED),
                method("list", CodeChanges.CHANGED, CodeInventoryService.EXECUTED)));
        RuntimeInsightsService service = service();
        service.setDeclaredRoutes(
                () -> List.of(
                        new MappingDto("GET", "/orders", "shop.OrderService#list()", null, null),
                        new MappingDto("DELETE", "/orders", "shop.OrderService#pay()", null, null),
                        new MappingDto("DELETE", "/orders/{id}", "shop.Other#pay()", null, null)),
                null);

        assertThat(observations(service.report()))
                .singleElement()
                .satisfies(observation -> assertThat(observation.whatToCheck().get(0))
                        .contains("`DELETE /orders`")
                        .doesNotContain("GET /orders")
                        .doesNotContain("/orders/{id}"));
    }

    /**
     * Spring describes an inherited handler by its concrete controller, and the engine does not know the class
     * hierarchy: a changed base-controller method whose name another class's handler carries may be that handler, so
     * its check says no route is known, never that none is mapped.
     */
    @Test
    void aChangedMethodAnotherClassesHandlerMayInheritSaysNoRouteIsKnown() {
        code.set(changes(
                method("pay", CodeChanges.CHANGED, CodeInventoryService.NEVER_EXECUTED),
                method("list", CodeChanges.CHANGED, CodeInventoryService.EXECUTED)));
        RuntimeInsightsService service = service();
        service.setDeclaredRoutes(
                () -> List.of(
                        new MappingDto("GET", "/orders", "shop.OrderService#list()", null, null),
                        new MappingDto("POST", "/shop/pay", "shop.ShopController#pay()", null, null)),
                null);

        assertThat(observations(service.report()))
                .singleElement()
                .satisfies(observation -> assertThat(observation.whatToCheck().get(0))
                        .startsWith("No route is known to be mapped to it")
                        .doesNotContain("POST /shop/pay"));
    }

    /** A changed method no declared route maps to, such as a service method, names no route rather than a guess. */
    @Test
    void aChangedMethodNoRouteMapsToNamesNoRoute() {
        code.set(changes(
                method("pay", CodeChanges.CHANGED, CodeInventoryService.NEVER_EXECUTED),
                method("list", CodeChanges.CHANGED, CodeInventoryService.EXECUTED)));
        RuntimeInsightsService service = service();
        service.setDeclaredRoutes(
                () -> List.of(new MappingDto("GET", "/orders", "shop.OrderService#list()", null, null)), null);

        assertThat(observations(service.report()))
                .singleElement()
                .satisfies(observation -> assertThat(observation.whatToCheck().get(0))
                        .startsWith("No declared route is mapped to it")
                        .doesNotContain("GET /orders"));
    }

    @Test
    void aChangedMethodThatExecutedIsNotReported() {
        code.set(changes(method("pay", CodeChanges.CHANGED, CodeInventoryService.EXECUTED)));

        RuntimeInsightsReportDto report = service().report();

        assertThat(check(report).status()).isEqualTo("EVALUATED");
        assertThat(observations(report)).isEmpty();
    }

    @Test
    void anUntrackedChangedMethodIsLeftOutAndSaidSo() {
        code.set(changes(method("pay", CodeChanges.CHANGED, CodeInventoryService.NOT_TRACKED)));

        RuntimeInsightsReportDto report = service().report();

        assertThat(observations(report)).isEmpty();
        assertThat(check(report).reason()).contains("could not track");
    }

    @Test
    void isNotApplicableWithoutTheAgentOrAPreviousRunOrItsPanel() {
        RuntimeInsightsService service = service();
        code.set(new ChangedCode(
                "Requires the BootUI agent's inventory sensor: not attached.", false, null, List.of(), 0L, null, null));
        assertThat(check(service.report()).status()).isEqualTo("NOT_APPLICABLE");
        assertThat(check(service.report()).reason())
                .startsWith("This observation requires the BootUI agent's inventory");

        code.set(new ChangedCode(null, false, "No previous run.", List.of(), 0L, "COMPLETE", null));
        assertThat(check(service.report()).reason()).startsWith(ChangedCodeNotExecuted.NO_PREVIOUS_RUN);

        code.set(changes(method("pay", CodeChanges.CHANGED, CodeInventoryService.NEVER_EXECUTED)));
        panelEnabled = false;
        assertThat(check(service.report()).reason()).isEqualTo(RuntimeInsightsService.CODE_INVENTORY_DISABLED);

        RuntimeInsightsService without = new RuntimeInsightsService(journal, null, null, null, List::of);
        assertThat(check(without.report()).reason()).isEqualTo(ChangedCodeNotExecuted.REQUIRES_AGENT);
    }

    @Test
    void isNotApplicableWhileTheScanRunsOrAfterItFailedRatherThanCleanOrWithoutAPreviousRun() {
        RuntimeInsightsService service = service();
        for (String running : List.of(CodeInventoryService.PENDING, CodeInventoryService.RUNNING)) {
            // Before the scan compared anything, there is a previous run but nothing to say yet.
            code.set(new ChangedCode(
                    null, true, CodeInventoryService.SCAN_RUNNING, List.of(), running.hashCode(), running, null));
            RuntimeInsightCheckDto check = check(service.report());
            assertThat(check.status()).isEqualTo("NOT_APPLICABLE");
            assertThat(check.reason()).isEqualTo(ChangedCodeNotExecuted.SCAN_RUNNING);
        }

        code.set(new ChangedCode(null, true, null, List.of(), 7L, "FAILED", "The scan failed: IllegalStateException."));
        RuntimeInsightCheckDto failed = check(service.report());
        assertThat(failed.status()).isEqualTo("NOT_APPLICABLE");
        assertThat(failed.reason())
                .isEqualTo(ChangedCodeNotExecuted.SCAN_FAILED + " The scan failed: IllegalStateException.");
    }

    @Test
    void aCheapFingerprintDecidesWhenTheChangesAreAskedForAgain() {
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicLong fingerprint = new java.util.concurrent.atomic.AtomicLong(1L);
        code.set(changes(method("pay", CodeChanges.CHANGED, CodeInventoryService.NEVER_EXECUTED)));
        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, panel -> panelEnabled, null, List::of);
        service.setCodeInventory(
                () -> {
                    asked.incrementAndGet();
                    return code.get();
                },
                fingerprint::get);
        assertThat(observations(service.report())).hasSize(1);
        int first = asked.get();

        service.report();
        service.report();
        assertThat(asked.get())
                .as("an unchanged fingerprint reuses the projection")
                .isEqualTo(first);

        code.set(changes(method("pay", CodeChanges.CHANGED, CodeInventoryService.EXECUTED)));
        fingerprint.set(2L);
        assertThat(observations(service.report())).isEmpty();
        assertThat(asked.get()).isGreaterThan(first);
    }

    @Test
    void aMethodThatRunsLaterClearsTheFindingWithoutAJournalEvent() {
        code.set(changes(method("pay", CodeChanges.CHANGED, CodeInventoryService.NEVER_EXECUTED)));
        RuntimeInsightsService service = service();
        assertThat(observations(service.report())).hasSize(1);

        code.set(new ChangedCode(
                null,
                true,
                null,
                List.of(new ChangedClass(
                        "shop.OrderService",
                        List.of(method("pay", CodeChanges.CHANGED, CodeInventoryService.EXECUTED)),
                        List.of())),
                99L,
                "COMPLETE",
                null));

        assertThat(observations(service.report())).isEmpty();
    }
}
