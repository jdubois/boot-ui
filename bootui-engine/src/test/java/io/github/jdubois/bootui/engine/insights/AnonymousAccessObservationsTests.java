package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationRowDto;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.AuthorizationPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.OrmPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AnonymousAccessObservationsTests {

    private final RuntimeJournal journal = new RuntimeJournal(
            new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, JournalSource.all()),
            RunIdentity.start());
    private int requests;

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void anonymousSuccessfulWritesAreReportedPerTableButNeverReadsAuthenticatedWritesOrUncheckedRequests() {
        for (int i = 0; i < 2; i++) {
            request(
                    "POST",
                    "/api/reviews",
                    201,
                    decision("ANONYMOUS", true, null),
                    sql("insert into reviews values (?, ?)"));
        }
        request(
                "POST",
                "/api/reviews",
                201,
                decision("AUTHENTICATED", true, null),
                sql("insert into reviews values (?, ?)"));
        request("GET", "/api/products", 200, decision("ANONYMOUS", true, null), sql("select * from products"));
        request("POST", "/api/contact", 201, null, sql("insert into contact_messages values (?)"));
        request("POST", "/api/reviews", 403, decision("ANONYMOUS", false, null));

        RuntimeInsightsReportDto report = report();

        assertThat(byKind(report, AnonymousDataReach.KIND)).singleElement().satisfies(finding -> {
            assertThat(finding.subject()).isEqualTo("POST /api/reviews");
            assertThat(finding.sentence())
                    .isEqualTo("`POST /api/reviews` wrote table `reviews` in 2 of 2 successful anonymous requests.");
            assertThat(finding.whatToCheck())
                    .last()
                    .asString()
                    .startsWith("Do not add authorization from this row alone");
            assertThat(finding.limitations()).first().asString().contains("statement text");
            assertThat(finding.limitations()).noneMatch(limitation -> limitation.contains("orm source"));
        });
        assertThat(report.checks())
                .filteredOn(check -> check.kind().equals(AnonymousDataReach.KIND))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo("EVALUATED");
                    assertThat(check.reason()).isNull();
                });
    }

    @Test
    void anAnonymousSuccessIsReportedOnlyOnARouteWhoseRulesRestrictedAnotherRequest() {
        request("GET", "/api/orders/{id}", 200, decision("ANONYMOUS", true, null));
        request("GET", "/api/orders/{id}", 401, decision("ANONYMOUS", false, null));
        request("GET", "/api/catalog", 200, decision("ANONYMOUS", true, null));
        request("GET", "/api/catalog", 200, decision("ANONYMOUS", true, null));
        request("GET", "/api/admin", 200, decision("AUTHENTICATED", true, "hasAnyAuthority(ROLE_ADMIN)"));
        request("GET", "/api/reports", 200, decision("AUTHENTICATED", true, "hasAnyAuthority(ROLE_ADMIN)"));
        request("GET", "/api/reports", 200, decision("ANONYMOUS", true, null));

        RuntimeInsightsReportDto report = report();

        List<RuntimeObservationDto> findings = byKind(report, AnonymousSuccessOnRestrictedRoute.KIND);
        assertThat(findings)
                .extracting(RuntimeObservationDto::subject)
                .containsExactlyInAnyOrder("GET /api/orders/{id}", "GET /api/reports");
        RuntimeObservationDto orders = findings.stream()
                .filter(finding -> finding.subject().equals("GET /api/orders/{id}"))
                .findFirst()
                .orElseThrow();
        RuntimeObservationDto reports = findings.stream()
                .filter(finding -> finding.subject().equals("GET /api/reports"))
                .findFirst()
                .orElseThrow();
        assertThat(orders.sentence())
                .isEqualTo("`GET /api/orders/{id}` answered 1 anonymous request with 2xx, while its rules restricted 1"
                        + " other request: a successful anonymous response, not proof that the rule is wrong.");
        assertThat(new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null)
                        .insight(reports.id())
                        .rows())
                .extracting(RuntimeObservationRowDto::cells)
                .extracting(cells -> cells.get(2) + " " + cells.get(3))
                .containsExactly("granted ", "granted hasAnyAuthority(ROLE_ADMIN)");
    }

    @Test
    void anonymousWritesNameOnlyTheDmlTargetNeverSelectSubqueryOrFromSources() {
        request(
                "POST",
                "/api/write",
                200,
                decision("ANONYMOUS", true, null),
                sql("insert into audit_log select id from products"),
                sql("delete from cart_items where cart_id in (select id from carts)"),
                sql("update orders set customer_name = c.name from customers c where orders.customer_id = c.id"),
                sql(
                        "merge into stock using deliveries on stock.id = deliveries.id when matched then update set qty = ?"));

        assertThat(byKind(report(), AnonymousDataReach.KIND))
                .extracting(RuntimeObservationDto::sentence)
                .containsExactlyInAnyOrder(
                        "`POST /api/write` wrote table `audit_log` in 1 of 1 successful anonymous request.",
                        "`POST /api/write` wrote table `cart_items` in 1 of 1 successful anonymous request.",
                        "`POST /api/write` wrote table `orders` in 1 of 1 successful anonymous request.",
                        "`POST /api/write` wrote table `stock` in 1 of 1 successful anonymous request.");
    }

    @Test
    void quarkusPreparationsAreNeverProvenWritesAndTimedJdbcStillIs() {
        preparedRequest("/prepared", 1, false);
        preparedRequest("/unexecuted", 0, false);
        preparedRequest("/jdbc", 0, true);

        var report = new RuntimeInsightsService(journal, null, null, InsightsStack.QUARKUS, null).report();
        assertThat(byKind(report, AnonymousDataReach.KIND))
                .extracting(RuntimeObservationDto::sentence)
                .containsExactlyInAnyOrder(
                        "`POST /prepared` prepared a write statement targeting table `audit_log` in 1 of 1"
                                + " successful anonymous request.",
                        "`POST /jdbc` wrote table `audit_log` in 1 of 1 successful anonymous request.");
        assertThat(byKind(report, AnonymousDataReach.KIND))
                .filteredOn(finding -> finding.subject().equals("POST /prepared"))
                .singleElement()
                .satisfies(finding -> assertThat(finding.limitations())
                        .anyMatch(limitation -> limitation.contains("cannot prove this particular statement ran")));
    }

    @Test
    void hidingHibernateEvidenceCannotTurnAnUnverifiedPreparationIntoAWrite() {
        preparedRequest("/prepared", 1, false);
        preparedRequest("/jdbc", 0, true);
        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal, null, panel -> !panel.equals(BootUiPanels.HIBERNATE), InsightsStack.QUARKUS, null)
                .report();
        assertThat(byKind(report, AnonymousDataReach.KIND))
                .extracting(RuntimeObservationDto::subject)
                .containsExactly("POST /jdbc");
        assertThat(report.checks())
                .filteredOn(check -> check.kind().equals(AnonymousDataReach.KIND))
                .singleElement()
                .satisfies(check -> assertThat(check.reason()).contains("unverified Quarkus preparations"));
    }

    @Test
    void intendedSignupWritesAreFactsWithAnExplicitVerificationNotAnExclusion() {
        request("POST", "/signup", 201, decision("ANONYMOUS", true, null), sql("insert into users values (?)"));

        assertThat(byKind(report(), AnonymousDataReach.KIND)).singleElement().satisfies(finding -> {
            assertThat(finding.sentence()).contains("wrote table `users`");
            assertThat(finding.whatToCheck()).contains(AnonymousAccess.VERIFY);
        });
    }

    @Test
    void aJdbcBatchReportsEachWrittenTableRatherThanJustTheFirstStatement() {
        request(
                "POST",
                "/batch",
                200,
                decision("ANONYMOUS", true, null),
                sql("insert into audit_log select id from products;\n delete from payroll where id = ?"));

        assertThat(byKind(report(), AnonymousDataReach.KIND))
                .extracting(RuntimeObservationDto::sentence)
                .containsExactlyInAnyOrder(
                        "`POST /batch` wrote table `audit_log` in 1 of 1 successful anonymous request.",
                        "`POST /batch` wrote table `payroll` in 1 of 1 successful anonymous request.");
    }

    @Test
    void ambiguousMultiTableWritesStayVisibleAsCandidatesNeverAssertReadTablesWereWritten() {
        request(
                "POST",
                "/multi-write",
                200,
                decision("ANONYMOUS", true, null),
                sql("delete from audit_log, payroll using audit_log join payroll on true join products on true"),
                sql("delete a, b from audit_log a join payroll b on true"),
                sql("update audit_log a join payroll p on true set p.amount = ?"));

        var findings = byKind(report(), AnonymousDataReach.KIND);
        assertThat(findings).anyMatch(finding -> finding.sentence().contains("lexical candidate `payroll`"));
        assertThat(findings).allSatisfy(finding -> {
            assertThat(finding.sentence()).contains("lexical candidate").doesNotContain("wrote table");
            assertThat(finding.whatToCheck().get(0)).contains("Resolve the statement's write targets");
            assertThat(finding.limitations()).anyMatch(limit -> limit.contains("not proven writes"));
        });
    }

    @Test
    void provenWritesAreNotDowngradedWhenOtherStatementsOnlyNameACandidate() {
        request(
                "POST",
                "/mixed",
                200,
                decision("ANONYMOUS", true, null),
                sql("insert into payroll values (?)"),
                sql("update audit_log a join payroll p on true set a.note = ?"));

        assertThat(byKind(report(), AnonymousDataReach.KIND))
                .extracting(RuntimeObservationDto::sentence)
                .contains(
                        "`POST /mixed` wrote table `payroll` in 1 of 1 successful anonymous request.",
                        "`POST /mixed` executed write statements naming lexical candidate `payroll` in 1 of 1 successful anonymous request.");
    }

    @Test
    void truncatedOrOmittedBatchPreviewCoverageIsExplicitEvenWhenLaterTargetsAreUnknown() {
        request(
                "POST",
                "/truncated",
                200,
                decision("ANONYMOUS", true, null),
                sql("insert into audit_log values ('" + "x".repeat(230) + "…;\n delete from payroll"));
        request(
                "POST",
                "/omitted",
                200,
                decision("ANONYMOUS", true, null),
                sql("insert into audit_log values (?);\n -- … (+2 more statements)"));

        var report = report();
        assertThat(report.checks())
                .filteredOn(check -> check.kind().equals(AnonymousDataReach.KIND))
                .singleElement()
                .satisfies(check -> assertThat(check.reason())
                        .contains(
                                "2 successful anonymous requests",
                                "possibly truncated SQL previews",
                                "not every write target"));
        assertThat(byKind(report, AnonymousDataReach.KIND))
                .anySatisfy(finding ->
                        assertThat(finding.sentence()).contains("`POST /truncated`", "lexical candidate `payroll`"))
                .allSatisfy(finding -> assertThat(finding.sentence())
                        .contains("lexical candidate")
                        .doesNotContain("wrote table"))
                .allSatisfy(finding -> assertThat(finding.limitations())
                        .anyMatch(limit ->
                                limit.contains("at most five statements") && limit.contains("256 characters")));
    }

    @Test
    void unprovenAnonymityIsExcludedAndMakesTheCheckInsufficientOnEveryStackWithoutInventingFindings() {
        request("POST", "/unchecked", 200, null, sql("insert into users values (?)"));
        request("POST", "/unknown", 200, decision("UNKNOWN", true, null), sql("insert into users values (?)"));
        request("POST", "/none", 200, decision("NONE", true, null), sql("insert into users values (?)"));

        for (InsightsStack stack : InsightsStack.values()) {
            var report = new RuntimeInsightsService(journal, null, null, stack, null).report();
            assertThat(report.observations())
                    .noneMatch(observation -> observation.kind().startsWith("anonymous-"));
            assertThat(report.checks())
                    .filteredOn(check -> check.kind().startsWith("anonymous-"))
                    .hasSize(2)
                    .allSatisfy(check -> {
                        assertThat(check.status()).isEqualTo("INSUFFICIENT");
                        assertThat(check.eligibleRequests()).isZero();
                        assertThat(check.reason()).contains("No eligible work was recorded");
                    });
        }
    }

    @Test
    void withoutTheAuthorizationSourceBothChecksSayWhyTheyCannotRun() {
        RuntimeJournal withoutAuthorization = new RuntimeJournal(
                new RuntimeJournalSettings(
                        true, 100, 1_000_000, 100, 10, 10, java.util.EnumSet.of(JournalSource.HTTP, JournalSource.SQL)),
                RunIdentity.start());
        try {
            RuntimeInsightsReportDto report = new RuntimeInsightsService(
                            withoutAuthorization, null, null, InsightsStack.SPRING_MVC, null)
                    .report();
            assertThat(report.checks())
                    .filteredOn(check -> check.kind().startsWith("anonymous-"))
                    .hasSize(2)
                    .allSatisfy(check -> {
                        assertThat(check.status()).isEqualTo("NOT_APPLICABLE");
                        assertThat(check.reason()).contains("authorization");
                    });
        } finally {
            withoutAuthorization.close();
        }
    }

    private RuntimeInsightsReportDto report() {
        return new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null).report();
    }

    private static List<RuntimeObservationDto> byKind(RuntimeInsightsReportDto report, String kind) {
        return report.observations().stream()
                .filter(observation -> observation.kind().equals(kind))
                .toList();
    }

    private static AuthorizationPayload decision(String authentication, boolean granted, String rule) {
        return new AuthorizationPayload("REQUEST", null, rule, authentication, granted, 0);
    }

    private static SqlPayload sql(String sql) {
        return new SqlPayload(sql, null, "db", false);
    }

    private void preparedRequest(String path, int ormStatements, boolean timedJdbc) {
        String requestId = "r" + (++requests);
        CorrelationContext context = CorrelationContext.forRequest(requestId);
        journal.offer(RuntimeEvent.of(
                JournalSource.AUTHORIZATION,
                1_000,
                1_000,
                context,
                "http-1",
                null,
                false,
                decision("ANONYMOUS", true, null)));
        journal.offer(RuntimeEvent.of(
                JournalSource.SQL,
                1_000,
                timedJdbc ? 1_000_000 : 0,
                context,
                "http-1",
                null,
                false,
                sql("insert into audit_log select id from products")));
        journal.offer(RuntimeEvent.of(
                JournalSource.ORM,
                1_000,
                10_000_000,
                context,
                "http-1",
                null,
                false,
                new OrmPayload(null, ormStatements, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)));
        journal.offer(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000 + requests,
                5_000_000,
                context,
                "http-1",
                null,
                false,
                new HttpPayload("POST", path, path, null, 200)));
        try {
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private void request(
            String method,
            String template,
            int status,
            AuthorizationPayload decision,
            RuntimeEventPayload... children) {
        String requestId = "r" + (++requests);
        CorrelationContext context = CorrelationContext.forRequest(requestId);
        List<RuntimeEvent> events = new ArrayList<>();
        if (decision != null) {
            events.add(RuntimeEvent.of(
                    JournalSource.AUTHORIZATION, 1_000, 1_000, context, "http-1", null, false, decision));
        }
        for (RuntimeEventPayload child : children) {
            events.add(RuntimeEvent.of(JournalSource.SQL, 1_000, 1_000_000, context, "http-1", null, false, child));
        }
        events.add(RuntimeEvent.of(
                JournalSource.HTTP,
                1_000 + requests,
                5_000_000,
                context,
                "http-1",
                null,
                false,
                new HttpPayload(method, template.replace("{id}", "42"), template, null, status)));
        events.forEach(journal::offer);
        try {
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
}
