package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.CodeInventoryMethodDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCoverageDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsAgentReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.inventory.CodeChanges;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.ApplicationFrames;
import io.github.jdubois.bootui.engine.journal.AuthorizationPayload;
import io.github.jdubois.bootui.engine.journal.ConnectionPayload;
import io.github.jdubois.bootui.engine.journal.GcPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.LogPayload;
import io.github.jdubois.bootui.engine.journal.MessagingPayload;
import io.github.jdubois.bootui.engine.journal.OrmPayload;
import io.github.jdubois.bootui.engine.journal.RequestTiming;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeEventPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.ScheduledPayload;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.journal.TransactionPayload;
import io.github.jdubois.bootui.engine.journal.WebSocketPayload;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RuntimeInsightsServiceTests {

    private RuntimeJournal journal = journal(JournalSource.all());
    private int requests;

    @Test
    void anEmptySnapshotLeavesEveryCheckWithoutEligibleWork() {
        assertThat(service().report().checks()).isNotEmpty().allSatisfy(check -> {
            assertThat(check.eligibleRequests()).isZero();
            assertThat(check.status()).isNotIn("EVALUATED", "PARTIAL");
        });
    }

    @Test
    void aZeroEligibleCheckPreservesTheExplanationOfUncountedWork() {
        Observation observation = new Observation() {
            @Override
            public String kind() {
                return "uncounted-work";
            }

            @Override
            public String title() {
                return "Uncounted work";
            }

            @Override
            public CorrelationTier minimumTier() {
                return CorrelationTier.REQUEST_ID;
            }

            @Override
            public Set<JournalSource> reads() {
                return Set.of(JournalSource.HTTP);
            }

            @Override
            public Evaluation evaluate(InsightsSnapshot snapshot) {
                return new Evaluation(0, List.of(), "Recorded work could not be placed against its transaction.");
            }
        };
        RuntimeInsightsService service = new RuntimeInsightsService(
                journal, null, panel -> true, InsightsStack.SPRING_MVC, List::of, List.of(observation));
        assertThat(service.report().checks()).singleElement().satisfies(check -> {
            assertThat(check.status()).isEqualTo("INSUFFICIENT");
            assertThat(check.eligibleRequests()).isZero();
            assertThat(check.reason())
                    .contains(
                            "No eligible work was recorded",
                            "Recorded work could not be placed against its transaction.");
        });
    }

    @AfterEach
    void close() {
        journal.close();
    }

    @Test
    void repeatedSelectsAfterAParentStatementAreObservedOnceThreeRequestsShowThem() {
        for (int i = 0; i < 3; i++) {
            request(
                    "GET",
                    "/api/orders/{id}",
                    sqls("select * from orders where id = 1", 6, "select * from lines where order_id = ?"));
        }
        request(
                "GET",
                "/api/orders/{id}",
                sqls("select * from orders where id = 2", 2, "select * from lines where order_id = ?"));
        // Counterexample: a loop with no parent statement before it.
        for (int i = 0; i < 3; i++) {
            request("GET", "/api/batch", sqls(null, 6, "select * from items where id = ?"));
        }
        // Insufficient: one request repeats on another route.
        request(
                "GET",
                "/api/customers/{id}",
                sqls("select * from customers where id = 7", 5, "select * from notes where customer_id = ?"));

        RuntimeInsightsReportDto report = service().report();

        Map<String, RuntimeObservationDto> byRoute = observations(report, RepeatedSelects.KIND);
        assertThat(byRoute).containsOnlyKeys("GET /api/orders/{id}", "GET /api/customers/{id}");
        RuntimeObservationDto orders = byRoute.get("GET /api/orders/{id}");
        assertThat(orders.status()).isEqualTo("OBSERVED");
        assertThat(orders.eligible()).isEqualTo(4);
        assertThat(orders.affected()).isEqualTo(3);
        assertThat(orders.sentence())
                .contains("`GET /api/orders/{id}` ran `select * from lines where order_id = ?` 5 or more times")
                .contains("3 of 4 requests, up to 6 times in one.");
        assertThat(orders.minimumTier()).isEqualTo("REQUEST_ID");
        assertThat(orders.exemplarRequestIds()).hasSize(3);
        assertThat(orders.id()).matches("repeated-selects:[0-9a-f]{10}");
        assertThat(byRoute.get("GET /api/customers/{id}").status()).isEqualTo("INSUFFICIENT");
        assertThat(byRoute.get("GET /api/customers/{id}").sentence()).contains("1 of 3 requests needed");
        assertThat(report.observations().get(0).status())
                .as("sufficient findings first")
                .isEqualTo("OBSERVED");
    }

    @Test
    void anObservationsIdIsStableAcrossRefreshesAndItsDetailListsItsEvidence() {
        for (int i = 0; i < 3; i++) {
            request(
                    "GET",
                    "/api/orders/{id}",
                    sqls("select 1 from orders", 5, "select * from lines where order_id = ?"));
        }
        RuntimeInsightsService service = service();
        String id = observations(service.report(), RepeatedSelects.KIND)
                .get("GET /api/orders/{id}")
                .id();

        request("GET", "/api/health", sqls(null, 0, null));
        String again = observations(service.report(), RepeatedSelects.KIND)
                .get("GET /api/orders/{id}")
                .id();
        RuntimeObservationDetailDto detail = service.insight(id);

        assertThat(again).isEqualTo(id).matches("repeated-selects:[0-9a-f]{10}");
        assertThat(detail.available()).isTrue();
        assertThat(detail.columns())
                .containsExactly("Request", "Executions", "Time (ms)", "Call site", "Phase", "In transaction");
        assertThat(detail.rows()).hasSize(3).allSatisfy(row -> {
            assertThat(row.cells().get(1)).isEqualTo("5");
            assertThat(row.cells().get(4)).isEqualTo("unknown");
            assertThat(row.cells().get(5)).isEqualTo("unknown");
        });
        assertThat(detail.truncated()).isZero();
        assertThat(service.insight("repeated-selects:nothing").available()).isFalse();
    }

    /**
     * M5-4c: statements carrying the code-paths stamp of the method that issued them name it, beside the stack walk's
     * call site; the same fixture without stamps keeps today's text, columns, and checks.
     */
    @Test
    void repeatedSelectsNameTheMethodThatIssuedThemWhenStatementsCarryStamps() {
        int findAll = 42;
        int load = 43;
        long issued = io.github.jdubois.bootui.engine.codepaths.CodePathStamps.pack(7L, 2, findAll);
        long parent = io.github.jdubois.bootui.engine.codepaths.CodePathStamps.pack(7L, 1, load);
        for (int i = 0; i < 3; i++) {
            request(
                    "GET",
                    "/api/owners",
                    stamped("select * from owners", parent, 5, "select * from pets where owner_id = ?", issued));
        }
        RuntimeInsightsService service = service();
        Map<Integer, String> keys = Map.of(
                findAll, "shop.OwnerService#findAll()Ljava/util/List;",
                load, "shop.OwnerController#list()Ljava/lang/String;");
        service.setCodePaths(route -> null, () -> 1L, keys::get);

        RuntimeObservationDto owners =
                observations(service.report(), RepeatedSelects.KIND).get("GET /api/owners");
        assertThat(owners.sentence()).endsWith("up to 5 times in one. Issued by `OwnerService.findAll`.");
        assertThat(owners.whatToCheck().get(0)).contains("`OwnerService.findAll`");
        assertThat(owners.limitations()).anyMatch(limitation -> limitation.startsWith("The issuing method is"));
        RuntimeObservationDetailDto detail = service.insight(owners.id());
        assertThat(detail.columns()).endsWith("In transaction", "Issuing method");
        assertThat(detail.rows())
                .allSatisfy(row -> assertThat(row.cells().get(6)).isEqualTo("OwnerService.findAll"));

        // The Code Paths panel disabled names nothing, as if the statements carried no stamp.
        RuntimeInsightsService hidden =
                new RuntimeInsightsService(journal, null, panel -> !panel.equals(BootUiPanels.CODE_PATHS), null, null);
        hidden.setCodePaths(route -> null, () -> 1L, keys::get);
        assertThat(observations(hidden.report(), RepeatedSelects.KIND)
                        .get("GET /api/owners")
                        .sentence())
                .doesNotContain("Issued by");
    }

    /**
     * M5-11: Code Paths and Code Inventory are read under one read of their panels per projection, resolved through the
     * agent evidence contract, never once per call an observation makes.
     */
    @Test
    void agentEvidenceIsReadUnderOneReadOfItsPanelsPerProjection() {
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        io.github.jdubois.bootui.engine.journal.AgentEvidence evidence =
                new io.github.jdubois.bootui.engine.journal.AgentEvidence(
                        panel -> {
                            if (panel.equals(BootUiPanels.CODE_PATHS) || panel.equals(BootUiPanels.CODE_INVENTORY)) {
                                asked.incrementAndGet();
                            }
                            return true;
                        },
                        null);
        io.github.jdubois.bootui.engine.codepaths.CodePathsService codePaths =
                new io.github.jdubois.bootui.engine.codepaths.CodePathsService(
                        io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess.absent(),
                        () -> null,
                        () -> null,
                        evidence);
        CodeInventoryService inventory = new CodeInventoryService(
                io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess.absent(),
                () -> null,
                () -> null,
                null,
                null,
                null,
                null,
                null,
                evidence);
        for (int i = 0; i < 3; i++) {
            request("GET", "/api/a");
        }
        RuntimeInsightsService service = new RuntimeInsightsService(journal, null, panel -> true, null, null);
        service.setCodePathsService(() -> codePaths);
        service.setCodeInventoryService(() -> inventory);

        service.report();

        assertThat(asked.get()).as("one read of each store's panel").isEqualTo(2);
        try {
            codePaths.close();
            inventory.close();
        } catch (RuntimeException ignored) {
            // nothing started
        }
    }

    /**
     * I6: statements a repository or DAO method of the application's own ran, which the sensor instruments as a bean,
     * name the method that called the repository, through the repository method; without the route's tree, the finding
     * names the repository method and says the caller is unknown, never that repositories are not instrumented.
     */
    @Test
    void repeatedSelectsIssuedByAnApplicationRepositoryNameTheMethodThatCalledIt() {
        int byOwner = 44;
        long issued = io.github.jdubois.bootui.engine.codepaths.CodePathStamps.pack(7L, 3, byOwner);
        for (int i = 0; i < 3; i++) {
            request(
                    "GET",
                    "/api/owners",
                    stamped("select * from owners", 0L, 5, "select * from pets where owner_id = ?", issued));
        }
        RuntimeInsightsService service = service();
        String repository = "shop.PetRepository#byOwner(J)Ljava/util/List;";
        service.setCodePaths(
                route -> null,
                () -> 1L,
                id -> id == byOwner ? repository : null,
                (route, id) -> id == byOwner && route.equals("GET /api/owners")
                        ? new io.github.jdubois.bootui.engine.codepaths.IssuingMethod(
                                "shop.OwnerService#findAll()Ljava/util/List;", repository, false)
                        : null);

        RuntimeObservationDto owners =
                observations(service.report(), RepeatedSelects.KIND).get("GET /api/owners");
        assertThat(owners.sentence()).endsWith("Issued by `OwnerService.findAll`, through `PetRepository.byOwner`.");
        assertThat(owners.whatToCheck().get(0)).startsWith("Look at `OwnerService.findAll`");
        assertThat(owners.limitations())
                .anyMatch(limitation -> limitation.contains("names the first method above it that is not one"))
                .noneMatch(limitation -> limitation.contains("own methods are not instrumented"));
        assertThat(service.insight(owners.id()).rows())
                .allSatisfy(row -> assertThat(row.cells().get(6))
                        .isEqualTo("OwnerService.findAll (through PetRepository.byOwner)"));

        // Only the method keys: the repository method, said to be one.
        RuntimeInsightsService keysOnly = service();
        keysOnly.setCodePaths(route -> null, () -> 1L, id -> id == byOwner ? repository : null);
        RuntimeObservationDto named =
                observations(keysOnly.report(), RepeatedSelects.KIND).get("GET /api/owners");
        assertThat(named.sentence()).endsWith("Issued by `PetRepository.byOwner`.");
        assertThat(named.limitations())
                .anyMatch(limitation -> limitation.startsWith("A repository or DAO method of the application's own"));
    }

    /** The counterexample: the same statements without stamps keep today's finding exactly. */
    @Test
    void repeatedSelectsWithoutStampsKeepTodaysText() {
        for (int i = 0; i < 3; i++) {
            request(
                    "GET",
                    "/api/owners",
                    stamped("select * from owners", 0L, 5, "select * from pets where owner_id = ?", 0L));
        }
        RuntimeInsightsService service = service();
        service.setCodePaths(route -> null, () -> 1L, id -> "shop.OwnerService#findAll()Ljava/util/List;");

        RuntimeObservationDto owners =
                observations(service.report(), RepeatedSelects.KIND).get("GET /api/owners");
        assertThat(owners.sentence())
                .isEqualTo("`GET /api/owners` ran `select * from pets where owner_id = ?` 5 or more times after another"
                        + " statement in 3 of 3 requests, up to 5 times in one.");
        assertThat(owners.whatToCheck()).hasSize(2);
        assertThat(owners.limitations()).noneMatch(limitation -> limitation.startsWith("The issuing method is"));
        assertThat(service.insight(owners.id()).columns())
                .containsExactly("Request", "Executions", "Time (ms)", "Call site", "Phase", "In transaction");
    }

    private static Child[] stamped(String parent, long parentStamp, int repeats, String repeated, long stamp) {
        Child[] children = new Child[1 + repeats];
        children[0] = new Child(
                JournalSource.SQL,
                1_000_000,
                new SqlPayload(parent, "Parent.load:10", "db", false, null, null, -1, parentStamp));
        for (int r = 0; r < repeats; r++) {
            children[1 + r] = new Child(
                    JournalSource.SQL,
                    1_000_000,
                    new SqlPayload(repeated, "Child.load:20", "db", false, null, null, -1, stamp));
        }
        return children;
    }

    @Test
    void repeatedSelectsNamePhaseAndTransactionAndTheDefaultListAppliesTheFiftyMillisecondFloor() {
        for (int i = 0; i < 3; i++) {
            request(
                    "GET",
                    "/api/orders/{id}",
                    new Child(
                            JournalSource.TRANSACTION,
                            200,
                            new TransactionPayload("OrderService.find", false, false, false, 100)),
                    sql("select * from orders where id = ?", RequestPhase.HANDLER, 50, 1_000_000),
                    sql("select * from lines where order_id = ?", RequestPhase.HANDLER, 150, 1_000_000),
                    sql("select * from lines where order_id = ?", RequestPhase.HANDLER, 160, 1_000_000),
                    sql("select * from lines where order_id = ?", RequestPhase.HANDLER, 170, 1_000_000),
                    sql("select * from lines where order_id = ?", RequestPhase.HANDLER, 180, 1_000_000),
                    sql("select * from lines where order_id = ?", RequestPhase.HANDLER, 190, 1_000_000));
        }
        request(
                "GET",
                "/api/outside",
                sql("select * from orders where id = ?", RequestPhase.FILTERS, 40, 10_000_000),
                sql("select * from lines where order_id = ?", RequestPhase.RESPONSE, 80, 10_000_000),
                sql("select * from lines where order_id = ?", RequestPhase.RESPONSE, 90, 10_000_000),
                sql("select * from lines where order_id = ?", RequestPhase.RESPONSE, 100, 10_000_000),
                sql("select * from lines where order_id = ?", RequestPhase.RESPONSE, 110, 10_000_000),
                sql("select * from lines where order_id = ?", RequestPhase.RESPONSE, 120, 10_000_000));
        request(
                "GET",
                "/api/unmeasured",
                sql("select 1 from parents", null, -1, 0),
                sql("select * from children where id = ?", null, -1, 0),
                sql("select * from children where id = ?", null, -1, 0),
                sql("select * from children where id = ?", null, -1, 0),
                sql("select * from children where id = ?", null, -1, 0),
                sql("select * from children where id = ?", null, -1, 0));
        request(
                "GET",
                "/api/unknown",
                sql("select 1 from parents", RequestPhase.HANDLER, -1, -1),
                sql("select * from children where id = ?", RequestPhase.HANDLER, -1, -1),
                sql("select * from children where id = ?", RequestPhase.HANDLER, -1, -1),
                sql("select * from children where id = ?", RequestPhase.HANDLER, -1, -1),
                sql("select * from children where id = ?", RequestPhase.HANDLER, -1, -1),
                sql("select * from children where id = ?", RequestPhase.HANDLER, -1, -1));

        RuntimeInsightsService service = service();
        // The kind's own default-list floor, apart from its external validation, which leaves every row out (M4-20).
        service.assumeValidated();
        RuntimeInsightsReportDto report = service.report();
        Map<String, RuntimeObservationDto> byRoute = observations(report, RepeatedSelects.KIND);
        RuntimeObservationDto inside = byRoute.get("GET /api/orders/{id}");
        RuntimeObservationDetailDto insideDetail = service.insight(inside.id());
        assertThat(insideDetail.rows()).allSatisfy(row -> {
            assertThat(row.cells().get(4)).isEqualTo("handler");
            assertThat(row.cells().get(5)).isEqualTo("yes");
        });
        assertThat(inside.limitations())
                .contains(RepeatedSelects.RESULT_SIZE_UNRECORDED)
                .doesNotContain(RepeatedSelects.UNDER_DEFAULT_FLOOR);

        // Repeated while the response was written, outside every transaction: SQL after the handler returned reports
        // the same statement from the same call site, with its cause, so Repeated SELECTs leaves it to it (M4-20).
        assertThat(byRoute).doesNotContainKey("GET /api/outside");
        assertThat(observations(report, LazySqlAfterHandler.KIND)).containsKey("GET /api/outside");
        assertThat(report.checks())
                .filteredOn(check -> check.kind().equals(RepeatedSelects.KIND))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.findings()).isEqualTo(3);
                    assertThat(check.reason())
                            .contains("1 statement that SQL after the handler returned reports on the same route");
                });

        assertThat(byRoute.get("GET /api/unmeasured").limitations())
                .contains(RepeatedSelects.UNMEASURED_REPEAT_TIME)
                .doesNotContain(RepeatedSelects.UNDER_DEFAULT_FLOOR);
        RuntimeObservationDetailDto unknown =
                service.insight(byRoute.get("GET /api/unknown").id());
        assertThat(unknown.rows())
                .singleElement()
                .satisfies(row -> assertThat(row.cells().get(2)).isEqualTo("unknown"));
        assertThat(byRoute.get("GET /api/unknown").limitations())
                .contains(RepeatedSelects.UNKNOWN_REPEAT_TIME)
                .doesNotContain(RepeatedSelects.UNDER_DEFAULT_FLOOR);

        RuntimeInsightsAgentReportDto list = RuntimeInsightsAgentView.list(report, "", 20);
        assertThat(list.observations())
                .filteredOn(observation -> RepeatedSelects.KIND.equals(observation.kind()))
                .extracting(observation -> observation.subject())
                .contains("GET /api/orders/{id}", "GET /api/unmeasured", "GET /api/unknown");
        assertThat(list.limitations()).noneMatch(limitation -> limitation.contains("Left out"));
        assertThat(RuntimeInsightsAgentView.list(report, "repeated-selects", 20).observations())
                .extracting(observation -> observation.subject())
                .contains("GET /api/orders/{id}");
    }

    /**
     * PetClinic's pet form (M4-20's adjudication follow-up 1): the handler loads the pet types once, then the view's
     * formatter loads them again for each option while the template renders. Repeated SELECTs names the formatter, where
     * the repeats ran after the handler returned, not the handler; and with SQL after the handler returned reporting the
     * same statement from the same call site, with its cause, Repeated SELECTs leaves it to that row.
     */
    @Test
    void repeatsWhileAViewRendersTakeTheFormattersCallSiteAndAreLeftToSqlAfterTheHandler() {
        String handler =
                "org.springframework.samples.petclinic.owner.PetController.populatePetTypes(PetController.java:64)";
        String formatter =
                "org.springframework.samples.petclinic.owner.PetTypeFormatter.print(PetTypeFormatter.java:53)";
        for (int i = 0; i < 3; i++) {
            List<Child> children = new ArrayList<>();
            children.add(new Child(
                    JournalSource.SQL,
                    1_000_000,
                    new SqlPayload(
                            "select * from owners where id = ?",
                            "Owners.find:1",
                            "db",
                            false,
                            null,
                            RequestPhase.HANDLER,
                            100)));
            children.add(new Child(
                    JournalSource.SQL,
                    1_000_000,
                    new SqlPayload(
                            "select * from types order by name",
                            handler,
                            "db",
                            false,
                            null,
                            RequestPhase.HANDLER,
                            200)));
            for (int r = 0; r < 5; r++) {
                children.add(new Child(
                        JournalSource.SQL,
                        1_000_000,
                        new SqlPayload(
                                "select * from types order by name",
                                null,
                                "db",
                                false,
                                ApplicationFrames.of(List.of(
                                        formatter,
                                        "org.thymeleaf.spring6.processor.SpringOptionFieldTagProcessor.doProcess("
                                                + "SpringOptionFieldTagProcessor.java:61)")),
                                RequestPhase.RESPONSE,
                                300 + r)));
            }
            request("GET", "/owners/{id}/pets/new", children.toArray(Child[]::new));
        }

        RuntimeInsightsService repeatsOnly = new RuntimeInsightsService(
                journal, null, null, InsightsStack.SPRING_MVC, null, List.of(new RepeatedSelects()));
        RuntimeObservationDto repeated =
                observations(repeatsOnly.report(), RepeatedSelects.KIND).get("GET /owners/{id}/pets/new");
        assertThat(repeatsOnly.insight(repeated.id()).rows())
                .hasSize(3)
                .allSatisfy(row -> assertThat(row.cells().get(3)).isEqualTo(formatter));

        RuntimeInsightsReportDto report =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null).report();
        assertThat(observations(report, RepeatedSelects.KIND)).doesNotContainKey("GET /owners/{id}/pets/new");
        assertThat(observations(report, LazySqlAfterHandler.KIND)
                        .get("GET /owners/{id}/pets/new")
                        .sentence())
                .contains("while the view was rendered");
    }

    /**
     * The mixed case: the handler runs an N+1 at its own call site, and the view repeats the same statement from a
     * formatter. SQL after the handler returned reports the view's repeats, but the handler's N+1 is not covered, so
     * Repeated SELECTs keeps reporting it, with the handler's call site.
     */
    @Test
    void aHandlersOwnNPlusOneStaysReportedWhenTheViewRepeatsTheSameStatementElsewhere() {
        String loop = "com.example.OrderController.lines(OrderController.java:40)";
        String formatter = "com.example.LineFormatter.print(LineFormatter.java:12)";
        for (int i = 0; i < 3; i++) {
            List<Child> children = new ArrayList<>();
            children.add(new Child(
                    JournalSource.SQL,
                    1_000_000,
                    new SqlPayload(
                            "select * from orders", "Orders.find:1", "db", false, null, RequestPhase.HANDLER, 100)));
            for (int r = 0; r < 6; r++) {
                children.add(new Child(
                        JournalSource.SQL,
                        1_000_000,
                        new SqlPayload(
                                "select * from lines where order_id = ?",
                                loop,
                                "db",
                                false,
                                null,
                                RequestPhase.HANDLER,
                                200 + r)));
            }
            for (int r = 0; r < 5; r++) {
                children.add(new Child(
                        JournalSource.SQL,
                        1_000_000,
                        new SqlPayload(
                                "select * from lines where order_id = ?",
                                null,
                                "db",
                                false,
                                ApplicationFrames.of(List.of(
                                        formatter,
                                        "org.thymeleaf.engine.ProcessorTemplateHandler.handleText("
                                                + "ProcessorTemplateHandler.java:562)")),
                                RequestPhase.RESPONSE,
                                300 + r)));
            }
            request("GET", "/orders/{id}", children.toArray(Child[]::new));
        }

        RuntimeInsightsService service =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null);
        RuntimeInsightsReportDto report = service.report();

        assertThat(observations(report, LazySqlAfterHandler.KIND)).containsKey("GET /orders/{id}");
        RuntimeObservationDto repeated =
                observations(report, RepeatedSelects.KIND).get("GET /orders/{id}");
        assertThat(repeated).as("the handler's N+1 is still reported").isNotNull();
        assertThat(repeated.limitations()).doesNotContain(RepeatedSelects.REPEATED_AFTER_HANDLER);
        assertThat(service.insight(repeated.id()).rows())
                .allSatisfy(row -> assertThat(row.cells().get(3)).isEqualTo(loop));
    }

    @Test
    void aSufficientLocalNPlusOneStaysInTheDefaultListAndAWeakCheapRepeatDoesNot() {
        for (int i = 0; i < 3; i++) {
            request("GET", "/api/orders/line-by-line", lineByLine(6, 200_000));
        }
        request("GET", "/api/cheap", lineByLine(5, 1_000_000));
        request("GET", "/api/hot", lineByLine(RepeatedSelects.HIGH_REPEAT_KEEP, 200_000));

        RuntimeInsightsService service = service();
        service.assumeValidated();
        RuntimeInsightsReportDto report = service.report();
        Map<String, RuntimeObservationDto> byRoute = observations(report, RepeatedSelects.KIND);
        assertThat(byRoute.get("GET /api/orders/line-by-line").status()).isEqualTo("OBSERVED");
        assertThat(byRoute.get("GET /api/orders/line-by-line").limitations())
                .doesNotContain(RepeatedSelects.UNDER_DEFAULT_FLOOR);
        assertThat(byRoute.get("GET /api/hot").status()).isEqualTo("INSUFFICIENT");
        assertThat(byRoute.get("GET /api/hot").limitations()).doesNotContain(RepeatedSelects.UNDER_DEFAULT_FLOOR);
        assertThat(byRoute.get("GET /api/cheap").limitations()).contains(RepeatedSelects.UNDER_DEFAULT_FLOOR);

        RuntimeInsightsAgentReportDto list = RuntimeInsightsAgentView.list(report, "", 20);
        assertThat(list.observations())
                .filteredOn(observation -> RepeatedSelects.KIND.equals(observation.kind()))
                .extracting(observation -> observation.subject())
                .contains("GET /api/orders/line-by-line", "GET /api/hot")
                .doesNotContain("GET /api/cheap");
        assertThat(list.limitations()).anyMatch(limitation -> limitation.contains("Left out 1 repeated-selects row"));
        assertThat(RuntimeInsightsAgentView.list(report, "repeated-selects", 20).observations())
                .extracting(observation -> observation.subject())
                .contains("GET /api/cheap");
    }

    @Test
    void clearingTheRecordingInvalidatesTheCachedReportEvenWithoutAnyNewEvent() {
        for (int i = 0; i < 3; i++) {
            request(
                    "GET",
                    "/api/orders/{id}",
                    sqls("select 1 from orders", 5, "select * from lines where order_id = ?"));
        }
        RuntimeInsightsService service = service();
        String id = observations(service.report(), RepeatedSelects.KIND)
                .get("GET /api/orders/{id}")
                .id();
        assertThat(service.insight(id).available()).isTrue();

        journal.clear();

        assertThat(observations(service.report(), RepeatedSelects.KIND)).isEmpty();
        assertThat(service.report().window().retainedEvents()).isZero();
        assertThat(service.insight(id).available()).isFalse();
    }

    @Test
    void aReportReadWhileAClearIsStillRunningIsNotCachedAsTheClearedOne() throws Exception {
        request("GET", "/api/orders/{id}", sqls("select 1 from orders", 5, "select * from lines where order_id = ?"));
        RuntimeInsightsService service = service();
        service.report();
        java.util.concurrent.CountDownLatch inClear = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        journal.addListener(new io.github.jdubois.bootui.engine.journal.JournalListener() {
            @Override
            public void onEntries(List<io.github.jdubois.bootui.engine.journal.JournalEntry> entries) {}

            @Override
            public void onClear() {
                inClear.countDown();
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        Thread clearer = new Thread(journal::clear);
        clearer.start();
        assertThat(inClear.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        service.report();
        release.countDown();
        clearer.join();

        assertThat(service.report().window().retainedEvents()).isZero();
        assertThat(journal.status().clears()).isEqualTo(1);
    }

    @Test
    void writesInGetRequestsAreAskedAboutAndWritesInPostsOrFailedWritesAreNot() {
        request("GET", "/api/products/{id}", sqls("update product_views set n = n + 1 where id = 3", 0, null));
        request("POST", "/api/orders", sqls("insert into orders (id) values (5)", 0, null));
        request("GET", "/api/cart", failedWrite("delete from carts where id = 9"));

        Map<String, RuntimeObservationDto> byRoute = observations(service().report(), SafeMethodDml.KIND);

        assertThat(byRoute).containsOnlyKeys("GET /api/products/{id}");
        RuntimeObservationDto views = byRoute.get("GET /api/products/{id}");
        assertThat(views.status()).isEqualTo("OBSERVED");
        assertThat(views.sentence())
                .contains("executed `update product_views set n = n + ? where id = ?` in 1 of 1 request")
                .endsWith("or a change the caller asked for?");
    }

    @Test
    void aQuarkusPreparationCannotProveAWriteWhenTheHibernatePanelIsHidden() {
        request(
                "GET",
                "/api/products/{id}",
                new Child(JournalSource.SQL, 0, new SqlPayload("insert into audit (id) values (1)", null, "db", false)),
                new Child(JournalSource.ORM, 1_000, orm()));

        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal, null, panel -> !panel.equals(BootUiPanels.HIBERNATE), InsightsStack.QUARKUS, null)
                .report();

        assertThat(checks(report).get(SafeMethodDml.KIND).status()).isEqualTo("EVALUATED");
        assertThat(checks(report).get(SafeMethodDml.KIND).reason())
                .contains("hibernate")
                .contains("prepared statements cannot be verified");
        assertThat(observations(report, SafeMethodDml.KIND)).isEmpty();
    }

    @Test
    void aTimedJdbcWriteIsStillReportedWhenHibernateIsUnavailable() {
        request(
                "GET",
                "/api/products/{id}",
                new Child(
                        JournalSource.SQL,
                        1_000_000,
                        new SqlPayload("insert into audit (id) values (1)", null, "db", false)));

        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal, null, panel -> !panel.equals(BootUiPanels.HIBERNATE), InsightsStack.QUARKUS, null)
                .report();

        assertThat(observations(report, SafeMethodDml.KIND)).containsOnlyKeys("GET /api/products/{id}");
        assertThat(checks(report).get(SafeMethodDml.KIND).reason()).contains("timed JDBC executions still are");
    }

    @Test
    void springWritesDoNotReadHiddenHibernateEvidence() {
        request(
                "POST",
                "/api/reviews",
                new Child(
                        JournalSource.AUTHORIZATION,
                        1_000,
                        new AuthorizationPayload("REQUEST", null, null, "ANONYMOUS", true, 0)),
                new Child(
                        JournalSource.SQL,
                        1_000_000,
                        new SqlPayload("insert into reviews values (1)", null, "db", false)));
        request("GET", "/api/views", sqls("update views set count = 1", 0, null));

        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal, null, panel -> !panel.equals(BootUiPanels.HIBERNATE), InsightsStack.SPRING_MVC, null)
                .report();

        for (String kind : List.of(AnonymousDataReach.KIND, SafeMethodDml.KIND)) {
            assertThat(checks(report).get(kind)).satisfies(check -> {
                assertThat(check.status()).isEqualTo("EVALUATED");
                assertThat(check.reason()).isNull();
            });
            assertThat(observations(report, kind)).hasSize(1).allSatisfy((route, finding) -> {
                assertThat(finding.status()).isEqualTo("OBSERVED");
                assertThat(finding.limitations()).noneMatch(limitation -> limitation.contains("hibernate"));
            });
        }
    }

    @Test
    void springWritesRemainEvaluatedWhenOnlyOrmEventsAreDropped() throws InterruptedException {
        journal.close();
        journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 10_000, 50_000_000, 10, 10, 0, JournalSource.all()),
                RunIdentity.start());
        request(
                "POST",
                "/api/reviews",
                new Child(
                        JournalSource.AUTHORIZATION,
                        1_000,
                        new AuthorizationPayload("REQUEST", null, null, "ANONYMOUS", true, 0)),
                new Child(
                        JournalSource.SQL,
                        1_000_000,
                        new SqlPayload("insert into reviews values (1)", null, "db", false)));
        request("GET", "/api/views", sqls("update views set count = 1", 0, null));

        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        journal.addListener(entries -> {
            blocked.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            RuntimeEvent orm = RuntimeEvent.of(
                    JournalSource.ORM,
                    2_000,
                    1_000,
                    CorrelationContext.forRequest("other"),
                    "http-1",
                    null,
                    false,
                    orm());
            assertThat(journal.offer(orm)).isTrue();
            assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 10; i++) {
                assertThat(journal.offer(orm)).isTrue();
            }
            assertThat(journal.offer(orm)).isFalse();
        } finally {
            release.countDown();
        }
        drain();

        assertThat(journal.status().dropped())
                .containsOnlyKeys(JournalSource.ORM)
                .containsEntry(JournalSource.ORM, 1L);
        RuntimeInsightsReportDto report =
                new RuntimeInsightsService(journal, null, null, InsightsStack.SPRING_MVC, null).report();
        for (String kind : List.of(AnonymousDataReach.KIND, SafeMethodDml.KIND)) {
            assertThat(checks(report).get(kind)).satisfies(check -> {
                assertThat(check.status()).isEqualTo("EVALUATED");
                assertThat(check.reason()).isNull();
            });
            assertThat(observations(report, kind)).hasSize(1).allSatisfy((route, finding) -> {
                assertThat(finding.status()).isEqualTo("OBSERVED");
                assertThat(finding.limitations()).noneMatch(limitation -> limitation.contains("dropped"));
            });
        }
    }

    @Test
    void aSentenceAndItsEvidenceQuoteAStatementsShapeNeverAValueItsFingerprintKeeps() {
        // A fingerprint keeps identifier-like "..." runs, which MySQL reads as string literals, and a truncated dollar
        // quote verbatim: it groups statements but is never shown.
        request("GET", "/api/products/{id}", sqls("insert into users(pw) values(\"hunter2\")", 0, null));
        for (int i = 0; i < 3; i++) {
            request(
                    "GET",
                    "/api/notes/{id}",
                    sqls("select 1 from notes", 5, "select * from t where body = $$sk_live_EXAMPLE"));
        }
        RuntimeInsightsService service = service();
        RuntimeInsightsReportDto report = service.report();

        RuntimeObservationDto write = observations(report, SafeMethodDml.KIND).get("GET /api/products/{id}");
        RuntimeObservationDto repeat =
                observations(report, RepeatedSelects.KIND).get("GET /api/notes/{id}");
        assertThat(write.sentence()).contains("executed `insert into users(pw) values(?)`");
        assertThat(repeat.sentence()).contains("ran `select * from t where body = ?`");
        for (RuntimeObservationDto observation : report.observations()) {
            assertThat(observation.sentence()).doesNotContain("hunter2", "sk_live_EXAMPLE");
            RuntimeObservationDetailDto detail = service.insight(observation.id());
            assertThat(detail.rows())
                    .allSatisfy(row ->
                            assertThat(String.join(" ", row.cells())).doesNotContain("hunter2", "sk_live_EXAMPLE"));
        }
    }

    @Test
    void connectionsHeldTogetherAreFoundAndBackToBackConnectionsAreNot() {
        // Nested: an inner connection checked out and released while the outer one is held.
        request("POST", "/api/orders", connection("db", 1_000, 50_000_000), connection("db", 10_000_000, 5_000_000));
        // Sequential: the second checked out exactly when the first was released.
        request("POST", "/api/payments", connection("db", 0, 10_000_000), connection("db", 10_000_000, 10_000_000));
        // Two data sources at once are two pools, each holding one.
        request("POST", "/api/report", connection("db", 0, 10_000_000), connection("audit", 1_000, 5_000_000));

        RuntimeInsightsService insights = service();
        insights.setPoolSizes(name -> name.equals("db") ? 10 : null);
        Map<String, RuntimeObservationDto> byRoute = observations(insights.report(), ConnectionsPerRequest.KIND);

        assertThat(byRoute).containsOnlyKeys("POST /api/orders");
        assertThat(byRoute.get("POST /api/orders").sentence())
                .isEqualTo("`POST /api/orders` held 2 connections of `db` at the same time in 1 of 1 request.");
        assertThat(byRoute.get("POST /api/orders").whatToCheck().get(1))
                .contains("pool of 10 connections", "10 concurrent requests", "estimate", "not proof");
        assertThat(observations(service().report(), ConnectionsPerRequest.KIND)
                        .get("POST /api/orders")
                        .whatToCheck()
                        .get(1))
                .contains("floor((P - 1) / 1) + 1");
    }

    @Test
    void connectionEstimateRoundsUpWithThreeHeldAndOneRequestCanExhaustASmallerPool() {
        request(
                "POST",
                "/api/orders",
                connection("db", 0, 30_000_000),
                connection("db", 1_000, 20_000_000),
                connection("db", 2_000, 10_000_000));
        RuntimeInsightsService insights = service();
        insights.setPoolSizes(name -> 10);
        assertThat(observations(insights.report(), ConnectionsPerRequest.KIND)
                        .get("POST /api/orders")
                        .whatToCheck()
                        .get(1))
                .contains("5 concurrent requests");
        RuntimeInsightsService small = service();
        small.setPoolSizes(name -> 1);
        assertThat(observations(small.report(), ConnectionsPerRequest.KIND)
                        .get("POST /api/orders")
                        .whatToCheck()
                        .get(1))
                .contains("1 concurrent request can");
    }

    @Test
    void aCheckWhoseSourceIsNotRecordedOrWhosePanelIsDisabledIsNotApplicableWithItsReason() {
        journal.close();
        journal = journal(EnumSet.of(JournalSource.HTTP, JournalSource.SQL));

        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal, null, panel -> !panel.equals(BootUiPanels.SQL_TRACE), null, null)
                .report();

        Map<String, RuntimeInsightCheckDto> checks =
                report.checks().stream().collect(Collectors.toMap(RuntimeInsightCheckDto::kind, Function.identity()));
        assertThat(checks.get(ConnectionsPerRequest.KIND).status()).isEqualTo("NOT_APPLICABLE");
        assertThat(checks.get(ConnectionsPerRequest.KIND).reason()).contains("does not record the connection source");
        assertThat(checks.get(RepeatedSelects.KIND).reason()).contains("panel, whose evidence this reads, is disabled");
        assertThat(report.observations()).isEmpty();
    }

    @Test
    void theReportStatesItsWindowAndCoverageAndADisabledJournalSaysWhy() {
        request("GET", "/api/orders/{id}", sqls("select 1", 0, null));
        journal.offer(RuntimeEvent.of(
                JournalSource.SQL,
                1,
                1,
                CorrelationContext.NONE,
                "pool-1",
                null,
                false,
                new SqlPayload("select 2", null, "db", false)));
        drain();

        RuntimeInsightsReportDto report = service().report();

        assertThat(report.available()).isTrue();
        assertThat(report.window().requests()).isEqualTo(1);
        assertThat(report.window().runId()).isEqualTo(journal.run().id());
        assertThat(report.coverage())
                .filteredOn(coverage -> coverage.source().equals("sql"))
                .singleElement()
                .satisfies(coverage -> {
                    assertThat(coverage.byRequestId()).isEqualTo(1);
                    assertThat(coverage.unlinked()).isEqualTo(1);
                });
        assertThat(report.checks())
                .filteredOn(check -> !check.kind().equals(AiUsageByRoute.KIND)
                        && !check.kind().equals(ProxyBypass.KIND)
                        && !check.kind().equals(WorkAfterResponse.KIND)
                        && !check.kind().equals(ChangedCodeNotExecuted.KIND))
                .allSatisfy(check -> {
                    assertThat(check.status()).as(check.kind()).isIn("INSUFFICIENT", "EVALUATED");
                    if ("INSUFFICIENT".equals(check.status())) {
                        assertThat(check.eligibleRequests()).isZero();
                        assertThat(check.reason()).contains("No eligible work");
                    } else if (!HeapGrowthAfterGc.KIND.equals(check.kind())) {
                        assertThat(check.eligibleRequests()).isPositive();
                    }
                });
        assertThat(report.checks())
                .filteredOn(check -> check.kind().equals(WorkAfterResponse.KIND))
                .extracting(RuntimeInsightCheckDto::status)
                .as("without the BootUI agent, work handed to an executor is unseen")
                .containsExactly("NOT_APPLICABLE");
        assertThat(report.checks())
                .filteredOn(check -> check.kind().equals(ChangedCodeNotExecuted.KIND))
                .extracting(RuntimeInsightCheckDto::status)
                .as("without the BootUI agent, which methods ran is unseen")
                .containsExactly("NOT_APPLICABLE");
        assertThat(report.checks())
                .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                .extracting(RuntimeInsightCheckDto::status)
                .as("without a resolver of proxy boundaries, a bypass cannot be judged")
                .containsExactly("NOT_APPLICABLE");
        assertThat(report.checks())
                .filteredOn(check -> check.kind().equals(AiUsageByRoute.KIND))
                .extracting(RuntimeInsightCheckDto::status)
                .as("without tracing, AI usage is unknown rather than none")
                .containsExactly("NOT_APPLICABLE");
        assertThat(new RuntimeInsightsService(null, null, null, null, null)
                        .report()
                        .unavailableReason())
                .isEqualTo(RuntimeInsightsService.DISABLED);
    }

    @Test
    void checksThatReadSqlAreUnavailableWhereThisApplicationsSqlIsNotRecorded() {
        for (int i = 0; i < 3; i++) {
            request("GET", "/api/orders/{id}");
        }
        RuntimeInsightsService service = service();
        service.setSqlCapture(() -> SqlCapture.of(null, true, true));

        RuntimeInsightsReportDto report = service.report();

        Map<String, RuntimeInsightCheckDto> checks =
                report.checks().stream().collect(Collectors.toMap(RuntimeInsightCheckDto::kind, Function.identity()));
        for (String kind : new String[] {RepeatedSelects.KIND, SafeMethodDml.KIND, ConnectionsPerRequest.KIND}) {
            assertThat(checks.get(kind).status()).as(kind).isEqualTo("UNAVAILABLE");
            assertThat(checks.get(kind).reason()).as(kind).isEqualTo(SqlCapture.R2DBC_ONLY);
            assertThat(checks.get(kind).findings()).isZero();
        }
        assertThat(SqlCapture.R2DBC_ONLY)
                .contains("BootUI records JDBC statements")
                .contains("not R2DBC");
        RuntimeInsightCheckDto breakdown = checks.get(RouteTimeBreakdown.KIND);
        assertThat(breakdown.status())
                .as("without request phase measurements, no breakdown is eligible")
                .isEqualTo("INSUFFICIENT");
        assertThat(breakdown.eligibleRequests()).isZero();
        assertThat(breakdown.reason()).contains("No eligible work", SqlCapture.R2DBC_ONLY);
        assertThat(report.observations())
                .filteredOn(observation -> observation.kind().equals(RouteTimeBreakdown.KIND))
                .allSatisfy(observation -> {
                    assertThat(observation.status()).isEqualTo("INSUFFICIENT");
                    assertThat(observation.eligible()).isZero();
                    assertThat(observation.limitations())
                            .anySatisfy(limitation -> assertThat(limitation).contains(SqlCapture.R2DBC_ONLY));
                });

        service.setSqlCapture(SqlCapture::capturing);
        assertThat(service.report().checks())
                .filteredOn(check -> check.kind().equals(RepeatedSelects.KIND))
                .extracting(RuntimeInsightCheckDto::status)
                .as("the requests remain eligible for SELECT checks once SQL capture is available")
                .containsExactly("EVALUATED");
    }

    @Test
    void sqlCaptureNamesWhyStatementsAreNotRecorded() {
        SqlTraceRecorder disabled = new SqlTraceRecorder(false, true, false, false, 8, 100, 2000, 200, 5);
        SqlTraceRecorder unwrapped = new SqlTraceRecorder(true, true, false, false, 8, 100, 2000, 200, 5);
        SqlTraceRecorder wrapped = new SqlTraceRecorder(true, true, false, false, 8, 100, 2000, 200, 5);
        wrapped.registerDataSource("dataSource");

        assertThat(SqlCapture.of(null, true, false)).isEqualTo(SqlCapture.notRecorded(SqlCapture.NOT_RECORDED));
        assertThat(SqlCapture.of(disabled, true, false)).isEqualTo(SqlCapture.notRecorded(SqlCapture.DISABLED));
        assertThat(SqlCapture.of(unwrapped, true, true)).isEqualTo(SqlCapture.notRecorded(SqlCapture.R2DBC_ONLY));
        assertThat(SqlCapture.of(unwrapped, false, false))
                .as("on Quarkus, the recorder exists only with a JDBC data source")
                .isEqualTo(SqlCapture.capturing());
        assertThat(SqlCapture.of(wrapped, true, false)).isEqualTo(SqlCapture.capturing());
        assertThat(SqlCapture.of(wrapped, true, true))
                .isEqualTo(SqlCapture.recordedExcept(SqlCapture.R2DBC_NOT_RECORDED));
    }

    @Test
    void checksThatReadApplicationEventsAreUnavailableWhereThisApplicationsEventsAreNotRecorded() {
        for (int i = 0; i < 3; i++) {
            request("GET", "/api/orders/{id}");
        }
        RuntimeInsightsService service = service();
        service.setAppEventCapture(() -> AppEventCapture.notRecorded(AppEventCapture.CUSTOM_MULTICASTER));

        RuntimeInsightsReportDto report = service.report();

        Map<String, RuntimeInsightCheckDto> checks = checks(report);
        for (String kind : new String[] {TransactionalListenerSkipped.KIND, AfterCommitWrites.KIND}) {
            assertThat(checks.get(kind).status()).as(kind).isEqualTo("UNAVAILABLE");
            assertThat(checks.get(kind).reason()).as(kind).isEqualTo(AppEventCapture.CUSTOM_MULTICASTER);
            assertThat(checks.get(kind).eligibleRequests()).as(kind).isZero();
            assertThat(checks.get(kind).findings()).as(kind).isZero();
        }
        assertThat(AppEventCapture.CUSTOM_MULTICASTER)
                .contains("its own application event multicaster", "Spring Modulith", "does not record");
        assertThat(report.limitations()).contains(AppEventCapture.CUSTOM_MULTICASTER);
        assertThat(checks.get(RepeatedSelects.KIND).status())
                .as("a check reading no application event is evaluated as before")
                .isEqualTo("EVALUATED");

        service.setAppEventCapture(AppEventCapture::capturing);
        RuntimeInsightsReportDto recorded = service.report();
        assertThat(checks(recorded).get(TransactionalListenerSkipped.KIND).status())
                .as("once application events are recorded, the same requests are eligible")
                .isEqualTo("EVALUATED");
        assertThat(recorded.limitations()).doesNotContain(AppEventCapture.CUSTOM_MULTICASTER);

        service.setAppEventCapture(() -> {
            throw new IllegalStateException("closed");
        });
        assertThat(checks(service.report()).get(TransactionalListenerSkipped.KIND))
                .as("a capture that cannot be read never reads as recorded")
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo("UNAVAILABLE");
                    assertThat(check.reason()).contains("IllegalStateException");
                });
    }

    @Test
    void unrecordedApplicationEventsKeepWhatAlreadyExplainsTheirAbsence() {
        request("GET", "/api/orders/{id}");
        RuntimeInsightsService quarkus = new RuntimeInsightsService(journal, null, null, InsightsStack.QUARKUS, null);
        quarkus.setAppEventCapture(() -> AppEventCapture.notRecorded(null));
        assertThat(checks(quarkus.report())
                        .get(TransactionalListenerSkipped.KIND)
                        .status())
                .as("not applicable on Quarkus, whatever the capture says")
                .isEqualTo("NOT_APPLICABLE");

        journal = journal(EnumSet.of(JournalSource.HTTP, JournalSource.SQL, JournalSource.TRANSACTION));
        request("GET", "/api/orders/{id}");
        RuntimeInsightsService service = service();
        service.setAppEventCapture(() -> AppEventCapture.notRecorded(null));
        RuntimeInsightsReportDto report = service.report();
        assertThat(checks(report).get(TransactionalListenerSkipped.KIND).status())
                .as("the journal does not record the app-event source, which the check names")
                .isEqualTo("NOT_APPLICABLE");
        assertThat(report.limitations()).doesNotContain(AppEventCapture.CUSTOM_MULTICASTER);
    }

    @Test
    void aLiveExposureSwitchReProjectsTheQuotedTemplateAtAnUnchangedWatermark() {
        request(
                "GET",
                "/api/login",
                new Child(
                        JournalSource.LOG,
                        -1,
                        new LogPayload(
                                "org.springframework.web.Login", "WARN", "Login password=hunter2 refused", null)));
        ValueExposure[] exposure = {ValueExposure.FULL};
        RuntimeInsightsService service = service();
        service.setExposure(new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return exposure[0];
            }

            @Override
            public boolean maskSecrets() {
                return true;
            }
        });

        RuntimeObservationDto full =
                observations(service.report(), FrameworkWarningsByRoute.KIND).get("GET /api/login");
        exposure[0] = ValueExposure.MASKED;
        RuntimeObservationDto masked =
                observations(service.report(), FrameworkWarningsByRoute.KIND).get("GET /api/login");
        exposure[0] = ValueExposure.METADATA_ONLY;
        RuntimeObservationDto metadata =
                observations(service.report(), FrameworkWarningsByRoute.KIND).get("GET /api/login");

        assertThat(full.sentence()).contains("password=hunter2");
        assertThat(masked.sentence()).contains("password=").doesNotContain("hunter2");
        assertThat(metadata.sentence()).doesNotContain("password", "hunter2", "refused");
        assertThat(metadata.id()).isEqualTo(full.id());
        assertThat(service.insight(metadata.id()).toString()).doesNotContain("hunter2");
    }

    @Test
    void aNullExposurePolicyFailsClosedToMasked() {
        request(
                "GET",
                "/api/login",
                new Child(
                        JournalSource.LOG,
                        -1,
                        new LogPayload(
                                "org.springframework.web.Login", "WARN", "Login password=hunter2 refused", null)));
        RuntimeInsightsService service = service();
        service.setExposure(null);

        assertThat(observations(service.report(), FrameworkWarningsByRoute.KIND)
                        .get("GET /api/login")
                        .sentence())
                .doesNotContain("hunter2");
    }

    private RuntimeInsightsService service() {
        return new RuntimeInsightsService(journal, null, null, null, null);
    }

    private static Map<String, RuntimeObservationDto> observations(RuntimeInsightsReportDto report, String kind) {
        return report.observations().stream()
                .filter(observation -> observation.kind().equals(kind))
                .collect(Collectors.toMap(RuntimeObservationDto::subject, Function.identity()));
    }

    /** A parent statement, then {@code repeats} executions of {@code repeated}. */
    private static Child[] sqls(String parent, int repeats, String repeated) {
        Child[] children = new Child[(parent == null ? 0 : 1) + repeats];
        int i = 0;
        if (parent != null) {
            children[i++] =
                    new Child(JournalSource.SQL, 1_000_000, new SqlPayload(parent, "Parent.load:10", "db", false));
        }
        for (int r = 0; r < repeats; r++) {
            children[i++] =
                    new Child(JournalSource.SQL, 1_000_000, new SqlPayload(repeated, "Child.load:20", "db", false));
        }
        return children;
    }

    private static Child[] failedWrite(String sql) {
        return new Child[] {new Child(JournalSource.SQL, 1_000_000, new SqlPayload(sql, null, "db", true))};
    }

    private static Child connection(String dataSource, long checkoutNanos, long heldNanos) {
        return new Child(JournalSource.CONNECTION, heldNanos, new ConnectionPayload(dataSource, 0, 1, checkoutNanos));
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
                30_000_000,
                context,
                "http-1",
                null,
                false,
                new HttpPayload(method, template.replace("{id}", "42"), template, null, 200)));
        drain();
    }

    private void drain() {
        try {
            assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static RuntimeJournal journal(Set<JournalSource> sources) {
        return new RuntimeJournal(
                new RuntimeJournalSettings(true, 10_000, 50_000_000, 10_000, 10, 10, sources), RunIdentity.start());
    }

    @Test
    void aCheckReadingASourceV2AddedIsNotApplicableWhenTheOwningPanelIsDisabled() {
        request(
                "GET",
                "/api/orders/{id}",
                new Child(
                        JournalSource.AUTHORIZATION,
                        1_000,
                        new AuthorizationPayload("/api/orders/42", "alice", "hasRole", "USER", true, 1)),
                new Child(JournalSource.ORM, 1_000, orm()));

        Map<String, RuntimeInsightCheckDto> checks = checks(new RuntimeInsightsService(
                        journal,
                        null,
                        panel -> !panel.equals(BootUiPanels.SECURITY_LOGS) && !panel.equals(BootUiPanels.HIBERNATE),
                        null,
                        null)
                .report());

        assertThat(checks.get(AnonymousSuccessOnRestrictedRoute.KIND).status()).isEqualTo("NOT_APPLICABLE");
        assertThat(checks.get(AnonymousSuccessOnRestrictedRoute.KIND).reason())
                .isEqualTo("The security-logs panel, whose evidence this reads, is disabled.");
        assertThat(checks.get(OrmAutoFlush.KIND).status()).isEqualTo("NOT_APPLICABLE");
        assertThat(checks.get(OrmAutoFlush.KIND).reason())
                .isEqualTo("The hibernate panel, whose evidence this reads, is disabled.");
        assertThat(checks.get(RouteTimeBreakdown.KIND).reason())
                .as("a check that only reads the source as optional evidence says it is not counted")
                .contains("The security-logs panel is disabled, so its evidence is not counted.")
                .contains("The hibernate panel is disabled, so its evidence is not counted.");
    }

    @Test
    void aPanelTheApplicationCannotServeIsReportedUnavailableRatherThanDisabled() {
        request(
                "GET",
                "/api/orders/{id}",
                new Child(
                        JournalSource.AUTHORIZATION,
                        1_000,
                        new AuthorizationPayload("/api/orders/42", "alice", "hasRole", "USER", true, 1)),
                new Child(JournalSource.ORM, 1_000, orm()));

        RuntimeInsightsService insights = new RuntimeInsightsService(
                journal,
                null,
                panel -> !panel.equals(BootUiPanels.SECURITY_LOGS) && !panel.equals(BootUiPanels.HIBERNATE),
                null,
                null);
        insights.setPanelUnavailable(panel -> panel.equals(BootUiPanels.SECURITY_LOGS)
                ? "Quarkus security events are disabled. Set quarkus.security.events.enabled=true."
                : null);
        Map<String, RuntimeInsightCheckDto> checks = checks(insights.report());

        assertThat(checks.get(AnonymousSuccessOnRestrictedRoute.KIND).reason())
                .as("an unavailable panel names what would make it available instead of implying someone switched"
                        + " it off")
                .isEqualTo("The security-logs panel, whose evidence this reads, is not available in this"
                        + " application: Quarkus security events are disabled. Set"
                        + " quarkus.security.events.enabled=true.");
        assertThat(checks.get(OrmAutoFlush.KIND).reason())
                .as("a panel off with no reason is still simply disabled")
                .isEqualTo("The hibernate panel, whose evidence this reads, is disabled.");
        assertThat(checks.get(RouteTimeBreakdown.KIND).reason())
                .as("optional evidence makes the same distinction rather than reporting it as insufficient")
                .contains("The security-logs panel is not available in this application, so its evidence is not"
                        + " counted: Quarkus security events are disabled. Set"
                        + " quarkus.security.events.enabled=true.")
                .contains("The hibernate panel is disabled, so its evidence is not counted.");
    }

    @Test
    void anUnavailablePanelsEvidenceStaysOutOfTheProjection() {
        request(
                "GET",
                "/api/orders/{id}",
                new Child(
                        JournalSource.AUTHORIZATION,
                        1_000,
                        new AuthorizationPayload("/api/orders/42", null, "authenticated", null, true, 1)));

        RuntimeInsightsService insights = new RuntimeInsightsService(
                journal, null, panel -> !panel.equals(BootUiPanels.SECURITY_LOGS), null, null);
        insights.setPanelUnavailable(panel -> panel.equals(BootUiPanels.SECURITY_LOGS) ? "the reason" : null);

        assertThat(checks(insights.report())
                        .get(AnonymousSuccessOnRestrictedRoute.KIND)
                        .status())
                .as("naming why a panel is off never makes evidence it cannot serve visible")
                .isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void disablingOneBrokersPanelLeavesTheOthersCountedAndIsStillReported() {
        request("GET", "/api/orders/{id}", sqls("select 1", 0, null));

        Map<String, RuntimeInsightCheckDto> checks =
                checks(new RuntimeInsightsService(journal, null, panel -> !panel.equals(BootUiPanels.KAFKA), null, null)
                        .report());

        assertThat(checks.get(RouteTimeBreakdown.KIND).status())
                .as("the rabbitmq and jms panels still serve their evidence")
                .isNotEqualTo("NOT_APPLICABLE");
        assertThat(checks.get(RouteTimeBreakdown.KIND).reason())
                .contains("The kafka panel is disabled, so its evidence is not counted.");
    }

    @Test
    void aUnitOfWorkIsLeftOutWholeWhenThePanelThatOpensItIsDisabled() {
        request("GET", "/api/orders/{id}", sqls("select * from orders where id = 1", 0, null));

        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal, null, panel -> !panel.equals(BootUiPanels.HTTP_EXCHANGES), null, null)
                .report();

        Map<String, Long> events = report.coverage().stream()
                .collect(Collectors.toMap(RuntimeInsightCoverageDto::source, RuntimeInsightCoverageDto::events));
        assertThat(events.getOrDefault("http", 0L))
                .as("the disabled panel's own evidence, including the route and its status, is left out")
                .isZero();
        assertThat(events.getOrDefault("sql", 0L))
                .as("and so is the rest of the request it opened, which names that route too")
                .isZero();
        assertThat(report.observations())
                .as("nothing reports a route the http-exchanges panel no longer publishes")
                .noneMatch(observation -> String.valueOf(observation.subject()).contains("/api/orders"));
        assertThat(checks(report).get(RepeatedSelects.KIND).status()).isEqualTo("NOT_APPLICABLE");
        assertThat(checks(report).get(RepeatedSelects.KIND).reason()).contains("http-exchanges");
        assertThat(checks(report).get(SafeMethodDml.KIND).status()).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void aSurvivingScheduledRunDoesNotMakeHiddenHttpRequestsLookEvaluated() {
        request("GET", "/api/orders/{id}", sqls("select 1", 0, null));
        journal.offer(RuntimeEvent.of(
                JournalSource.SCHEDULED,
                2_000,
                1_000,
                CorrelationContext.forExecution("job-1"),
                "scheduler",
                null,
                false,
                new ScheduledPayload("Job.run", null)));
        drain();

        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal, null, panel -> !panel.equals(BootUiPanels.HTTP_EXCHANGES), null, null)
                .report();

        assertThat(checks(report).get(SafeMethodDml.KIND).status()).isEqualTo("NOT_APPLICABLE");
        assertThat(checks(report).get(SafeMethodDml.KIND).reason()).contains("http-exchanges");
        assertThat(checks(report).get(RepeatedSelects.KIND).status()).isEqualTo("EVALUATED");
        assertThat(checks(report).get(RepeatedSelects.KIND).eligibleRequests()).isEqualTo(1);
        assertThat(checks(report).get(RepeatedSelects.KIND).reason()).contains("http-exchanges");
    }

    @Test
    void aHiddenScheduledUnitDoesNotMakeSqlChecksLookEvaluated() {
        journal.offer(RuntimeEvent.of(
                JournalSource.SCHEDULED,
                2_000,
                1_000,
                CorrelationContext.forExecution("job-1"),
                "scheduler",
                null,
                false,
                new ScheduledPayload("Job.run", null)));
        drain();

        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal, null, panel -> !panel.equals(BootUiPanels.SCHEDULED), null, null)
                .report();
        assertThat(checks(report).get(RepeatedSelects.KIND).status()).isEqualTo("NOT_APPLICABLE");
        assertThat(checks(report).get(RepeatedSelects.KIND).reason()).contains("scheduled");
        assertThat(checks(report).get(SafeMethodDml.KIND).status()).isEqualTo("INSUFFICIENT");
        assertThat(checks(report).get(SafeMethodDml.KIND).reason())
                .contains("No eligible work")
                .doesNotContain("scheduled");
    }

    @Test
    void hiddenScheduledUnitsDoNotLimitFindingsAboutVisibleHttpRequests() {
        request("GET", "/api/orders/{id}", sqls("insert into audit (id) values (1)", 0, null));
        journal.offer(RuntimeEvent.of(
                JournalSource.SCHEDULED,
                2_000,
                1_000,
                CorrelationContext.forExecution("job-1"),
                "scheduler",
                null,
                false,
                new ScheduledPayload("Job.run", null)));
        drain();

        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal, null, panel -> !panel.equals(BootUiPanels.SCHEDULED), null, null)
                .report();
        assertThat(checks(report).get(SafeMethodDml.KIND).status()).isEqualTo("EVALUATED");
        assertThat(checks(report).get(SafeMethodDml.KIND).reason()).isNull();
        assertThat(observations(report, SafeMethodDml.KIND))
                .containsOnlyKeys("GET /api/orders/{id}")
                .allSatisfy((route, finding) ->
                        assertThat(finding.limitations()).noneMatch(limitation -> limitation.contains("scheduled")));
        assertThat(checks(report).get(RepeatedSelects.KIND).reason()).contains("scheduled");
    }

    @Test
    void anHttpDropIsCountedOnceWhenTheObservationRequiresHttp() throws InterruptedException {
        journal.close();
        journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 10_000, 50_000_000, 1, 10, 10, JournalSource.all()),
                RunIdentity.start());
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        journal.addListener(entries -> {
            blocked.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertThat(journal.offer(RuntimeEvent.of(
                            JournalSource.HTTP,
                            1_000,
                            1_000,
                            CorrelationContext.forRequest("r1"),
                            "http-1",
                            null,
                            false,
                            new HttpPayload("GET", "/first", "/first", null, 200))))
                    .isTrue();
            assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(journal.offer(RuntimeEvent.of(
                            JournalSource.HTTP,
                            2_000,
                            1_000,
                            CorrelationContext.forRequest("r2"),
                            "http-1",
                            null,
                            false,
                            new HttpPayload("GET", "/second", "/second", null, 200))))
                    .isTrue();
            assertThat(journal.offer(RuntimeEvent.of(
                            JournalSource.HTTP,
                            3_000,
                            1_000,
                            CorrelationContext.forRequest("r3"),
                            "http-1",
                            null,
                            false,
                            new HttpPayload("GET", "/dropped", "/dropped", null, 200))))
                    .isFalse();
        } finally {
            release.countDown();
        }
        drain();

        assertThat(journal.status().dropped()).containsEntry(JournalSource.HTTP, 1L);
        RuntimeInsightCheckDto check = checks(service().report()).get(SafeMethodDml.KIND);
        assertThat(check.status()).isEqualTo("PARTIAL");
        assertThat(check.reason()).contains("dropped 1 events").doesNotContain("dropped 2 events");
    }

    @ParameterizedTest
    @EnumSource(
            value = JournalSource.class,
            names = {"SCHEDULED", "MESSAGING", "WEBSOCKET"})
    void droppedExecutionAnchorsMakeExecutionObservationsPartial(JournalSource source) throws InterruptedException {
        useSingleSlotQueue();
        RuntimeEventPayload payload =
                switch (source) {
                    case SCHEDULED -> new ScheduledPayload("OrderJob.run", null);
                    case MESSAGING -> new MessagingPayload("kafka", false, "orders", false);
                    case WEBSOCKET -> WebSocketPayload.handled("/orders", "/orders", 0L, false);
                    default -> throw new AssertionError(source);
                };
        for (int i = 0; i < 3; i++) {
            CorrelationContext context = CorrelationContext.forExecution("execution-" + i);
            for (Child child : sqls("select * from orders", 6, "select * from lines where order_id = ?")) {
                assertThat(journal.offer(RuntimeEvent.of(
                                child.source(), 1_000, child.nanos(), context, "worker", null, false, child.payload())))
                        .isTrue();
                drain();
            }
            assertThat(journal.offer(
                            RuntimeEvent.of(source, 1_000, 30_000_000, context, "worker", null, false, payload)))
                    .isTrue();
            drain();
        }
        withUndispatchedDrop(
                RuntimeEvent.of(
                        source, 2_000, 1_000, CorrelationContext.forExecution("lost"), "worker", null, false, payload),
                () -> {
                    RuntimeInsightsReportDto report = service().report();
                    assertThat(checks(report).get(RepeatedSelects.KIND).status())
                            .isEqualTo("PARTIAL");
                    assertThat(checks(report).get(RepeatedSelects.KIND).reason())
                            .contains("dropped 1 events");
                    assertThat(observations(report, RepeatedSelects.KIND).values())
                            .singleElement()
                            .satisfies(row -> assertThat(row.status()).isEqualTo("PARTIAL"));
                    assertThat(checks(report).get(SafeMethodDml.KIND).status()).isEqualTo("EVALUATED");
                });
    }

    @Test
    void optionalSourceDropsAreNotReadWhenItsPanelIsDisabled() throws InterruptedException {
        useSingleSlotQueue();
        for (int i = 0; i < 6; i++) {
            assertThat(journal.offer(RuntimeEvent.of(
                            JournalSource.HTTP,
                            1_000,
                            30_000_000,
                            CorrelationContext.forRequest("r" + i),
                            "worker",
                            null,
                            false,
                            new HttpPayload(
                                    "GET",
                                    "/orders",
                                    "/orders",
                                    null,
                                    200,
                                    null,
                                    new RequestTiming(1_000_000_000, -1, 1_000_000, 29_000_000)))))
                    .isTrue();
            drain();
        }
        withUndispatchedDrop(sqlDrop(), () -> {
            RuntimeInsightsReportDto report = new RuntimeInsightsService(
                            journal, null, panel -> !panel.equals(BootUiPanels.SQL_TRACE), null, null)
                    .report();
            assertThat(report.window().droppedEvents()).isEqualTo(1);
            RuntimeInsightCheckDto check = checks(report).get(RouteTimeBreakdown.KIND);
            assertThat(check.status()).isEqualTo("EVALUATED");
            assertThat(check.reason()).contains("sql-trace", "disabled").doesNotContain("dropped");
        });
    }

    @Test
    void aDropWithoutANewWatermarkInvalidatesCachedCoverageFindingsAndDetails() throws InterruptedException {
        useSingleSlotQueue();
        for (int i = 0; i < 3; i++) {
            assertThat(journal.offer(RuntimeEvent.of(
                            JournalSource.SQL,
                            1_000,
                            1_000,
                            CorrelationContext.forRequest("r" + (requests + 1)),
                            "worker",
                            null,
                            false,
                            new SqlPayload("insert into audit (id) values (1)", null, "db", false))))
                    .isTrue();
            drain();
            request("GET", "/orders");
        }
        RuntimeInsightsService service = service();
        withUndispatchedDrop(
                sqlDrop(),
                () -> {
                    RuntimeInsightsReportDto before = service.report();
                    assertThat(checks(before).get(SafeMethodDml.KIND).status()).isEqualTo("EVALUATED");
                    assertThat(before.window().droppedEvents()).isZero();
                    long watermark = journal.status().lastSequence();
                    assertThat(journal.offer(sqlDrop())).isTrue();
                    assertThat(journal.offer(sqlDrop())).isFalse();
                    assertThat(journal.status().lastSequence()).isEqualTo(watermark);

                    RuntimeInsightsReportDto after = service.report();
                    assertThat(after).isNotSameAs(before);
                    assertThat(after.window().droppedEvents()).isEqualTo(1);
                    assertThat(after.coverage())
                            .filteredOn(row -> row.source().equals("sql"))
                            .singleElement()
                            .satisfies(row -> assertThat(row.dropped()).isEqualTo(1));
                    assertThat(checks(after).get(SafeMethodDml.KIND).status()).isEqualTo("PARTIAL");
                    RuntimeObservationDto finding =
                            observations(after, SafeMethodDml.KIND).get("GET /orders");
                    assertThat(finding.status()).isEqualTo("PARTIAL");
                    assertThat(service.insight(finding.id()).observation()).isEqualTo(finding);
                    assertThat(service.report()).isSameAs(after);
                },
                false);
    }

    @ParameterizedTest
    @EnumSource(
            value = JournalSource.class,
            names = {"HTTP", "SCHEDULED", "MESSAGING", "WEBSOCKET"})
    void executionAnchorDropsDoNotMakeRunLevelGcChecksPartial(JournalSource source) throws InterruptedException {
        useSingleSlotQueue();
        for (int i = 0; i < 5; i++) {
            assertThat(journal.offer(RuntimeEvent.of(
                            JournalSource.GC,
                            1_000 + i,
                            1_000,
                            null,
                            "gc",
                            null,
                            false,
                            new GcPayload("old", i, "major", "test", true, 200, 100, 200, 100))))
                    .isTrue();
            drain();
        }
        RuntimeEventPayload payload =
                switch (source) {
                    case HTTP -> new HttpPayload("GET", "/lost", "/lost", null, 200);
                    case SCHEDULED -> new ScheduledPayload("Job.run", null);
                    case MESSAGING -> new MessagingPayload("kafka", false, "orders", false);
                    case WEBSOCKET -> WebSocketPayload.handled("/orders", "/orders", 0L, false);
                    default -> throw new AssertionError(source);
                };
        withUndispatchedDrop(RuntimeEvent.of(source, 2_000, 1_000, null, "worker", null, false, payload), () -> {
            RuntimeInsightsReportDto report = service().report();
            assertThat(checks(report).get(HeapGrowthAfterGc.KIND).status()).isEqualTo("EVALUATED");
            assertThat(checks(report).get(HeapGrowthAfterGc.KIND).reason()).doesNotContain("dropped");
        });
    }

    private void useSingleSlotQueue() {
        journal.close();
        journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 10_000, 50_000_000, 1, 10, 10, JournalSource.all()),
                RunIdentity.start());
    }

    @Test
    void aScheduledDropDoesNotMakeCodeInventoryEvidencePartial() throws InterruptedException {
        useSingleSlotQueue();
        CodeInventoryMethodDto method = new CodeInventoryMethodDto(
                "shop.OrderService#pay()V",
                "shop",
                "shop.OrderService",
                "pay",
                "()V",
                CodeInventoryService.NEVER_EXECUTED,
                null,
                CodeChanges.CHANGED,
                null,
                null,
                null);
        RuntimeInsightsService service = service();
        service.setCodeInventory(() -> new CodeInventoryService.ChangedCode(
                null,
                true,
                null,
                List.of(new CodeInventoryService.ChangedClass("shop.OrderService", List.of(method), List.of())),
                1,
                "COMPLETE",
                null));
        withUndispatchedDrop(
                RuntimeEvent.of(
                        JournalSource.SCHEDULED,
                        2_000,
                        1_000,
                        CorrelationContext.forExecution("lost"),
                        "worker",
                        null,
                        false,
                        new ScheduledPayload("Job.run", null)),
                () -> {
                    RuntimeInsightsReportDto report = service.report();
                    assertThat(report.window().droppedEvents()).isEqualTo(1);
                    assertThat(checks(report).get(ChangedCodeNotExecuted.KIND).status())
                            .isEqualTo("EVALUATED");
                    assertThat(observations(report, ChangedCodeNotExecuted.KIND).values())
                            .singleElement()
                            .satisfies(row -> assertThat(row.status()).isEqualTo("OBSERVED"));
                });
    }

    private static RuntimeEvent sqlDrop() {
        return RuntimeEvent.of(
                JournalSource.SQL,
                2_000,
                1_000,
                CorrelationContext.forRequest("lost"),
                "worker",
                null,
                false,
                new SqlPayload("select * from orders", null, "db", false));
    }

    private void withUndispatchedDrop(RuntimeEvent event, Runnable assertions) throws InterruptedException {
        withUndispatchedDrop(event, assertions, true);
    }

    private void withUndispatchedDrop(RuntimeEvent event, Runnable assertions, boolean dropFirst)
            throws InterruptedException {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        journal.addListener(entries -> {
            blocked.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Dispatcher was not released");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        });
        try {
            assertThat(journal.offer(RuntimeEvent.of(
                            JournalSource.HTTP,
                            2_000,
                            1_000,
                            CorrelationContext.forRequest("blocker"),
                            "worker",
                            null,
                            false,
                            new HttpPayload("GET", "/blocker", "/blocker", null, 200))))
                    .isTrue();
            assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
            if (dropFirst) {
                assertThat(journal.offer(event)).isTrue();
                assertThat(journal.offer(event)).isFalse();
            }
            assertions.run();
        } finally {
            release.countDown();
        }
        drain();
    }

    @Test
    void unavailablePanelsWithoutHiddenUnitsDoNotChangeChecks() {
        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal,
                        null,
                        panel -> !panel.equals(BootUiPanels.JMS) && !panel.equals(BootUiPanels.SCHEDULED),
                        InsightsStack.QUARKUS,
                        null)
                .report();
        assertThat(checks(report).get(RepeatedSelects.KIND).status()).isEqualTo("INSUFFICIENT");
        assertThat(checks(report).get(RepeatedSelects.KIND).reason())
                .contains("No eligible work")
                .doesNotContain("jms", "scheduled");
    }

    @Test
    void aTraceOnlyAiCallOwnedByAHiddenHttpRequestIsNotUncorrelatedCoverage() {
        journal.offer(new RuntimeEvent(
                JournalSource.HTTP,
                1_000,
                200_000_000,
                "hidden",
                null,
                "trace-hidden",
                "http-1",
                null,
                false,
                new HttpPayload("GET", "/hidden", "/hidden", null, 200)));
        journal.offer(new RuntimeEvent(
                JournalSource.AI,
                1_010,
                1_000,
                null,
                null,
                "trace-hidden",
                "http-1",
                null,
                false,
                new AiPayload(AiPayload.CHAT, "openai", "model", 10L, 5L, "stop", false)));
        journal.offer(new RuntimeEvent(
                JournalSource.AI,
                1_010,
                1_000,
                null,
                null,
                "unowned-trace",
                "http-1",
                null,
                false,
                new AiPayload(AiPayload.CHAT, "openai", "model", 10L, 5L, "stop", false)));
        drain();

        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal, null, panel -> !panel.equals(BootUiPanels.HTTP_EXCHANGES), null, null)
                .report();
        assertThat(report.coverage())
                .filteredOn(coverage -> coverage.source().equals("ai"))
                .singleElement()
                .satisfies(coverage -> {
                    assertThat(coverage.events()).isEqualTo(1);
                    assertThat(coverage.unlinked()).isEqualTo(1);
                });
    }

    @Test
    void onePanelReadStandsForAWholeProjectionSoAToggleCannotMakeEvidenceLookAbsent() {
        request("GET", "/api/orders/{id}", sqls("select * from orders where id = 1", 0, null));
        Map<String, Integer> reads = new LinkedHashMap<>();

        RuntimeInsightsReportDto report = new RuntimeInsightsService(
                        journal,
                        null,
                        // A panel toggled between two reads of the same projection would let it drop a source's
                        // events and then evaluate as though the source were visible, which reads as absence.
                        panel -> reads.merge(panel, 1, Integer::sum) == 1,
                        null,
                        null)
                .report();

        assertThat(reads.values()).as("every owning panel is read exactly once").containsOnly(1);
        assertThat(report.checks()).isNotEmpty();
    }

    private static Map<String, RuntimeInsightCheckDto> checks(RuntimeInsightsReportDto report) {
        return report.checks().stream().collect(Collectors.toMap(RuntimeInsightCheckDto::kind, Function.identity()));
    }

    private static OrmPayload orm() {
        return new OrmPayload("default", 1, 1_000, 1, 1_000, 1, 1_000, 0, 0, 1, 1, 0, 0, 0, List.of());
    }

    /** A parent select, then {@code children} repeats of one child select, the sample's line-by-line N+1. */
    private static Child[] lineByLine(int children, long childNanos) {
        Child[] events = new Child[1 + children];
        events[0] = sql("select id, customer from insight_orders order by id", RequestPhase.HANDLER, 10, childNanos);
        for (int i = 0; i < children; i++) {
            events[i + 1] = sql(
                    "select sku, quantity from insight_order_lines where order_id = ?",
                    RequestPhase.HANDLER,
                    20L + i,
                    childNanos);
        }
        return events;
    }

    private static Child sql(String sql, RequestPhase phase, long completedNanos, long durationNanos) {
        return new Child(
                JournalSource.SQL,
                durationNanos,
                new SqlPayload(sql, "Repo.run:1", "db", false, null, phase, completedNanos));
    }

    private record Child(JournalSource source, long nanos, RuntimeEventPayload payload) {}
}
