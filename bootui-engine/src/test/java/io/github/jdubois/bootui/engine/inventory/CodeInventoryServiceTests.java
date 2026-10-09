package io.github.jdubois.bootui.engine.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.AgentRing;
import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import io.github.jdubois.bootui.core.dto.CodeInventoryAgentReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryChangesReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryDependencyDto;
import io.github.jdubois.bootui.core.dto.CodeInventoryMethodDto;
import io.github.jdubois.bootui.core.dto.CodeInventoryMethodsReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryReport;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentRecordDrainer;
import io.github.jdubois.bootui.engine.journal.AgentEvidence;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyInventory;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Code Inventory against the real bridge class from the test class path, driven as the agent would drive it: method ids
 * assigned and tracked as classes load, hit flags set as methods run, and first-hit and class-load records published.
 */
class CodeInventoryServiceTests {

    private static final String GREET = "shop.OrderService#greet(Ljava/lang/String;)Ljava/lang/String;";
    private static final String TOTAL = "shop.OrderService#total(II)I";
    private static final String NEVER = "shop.OrderService#neverCalled()V";
    private static final String INIT = "shop.OrderService#<init>()V";
    private static final String CLINIT = "shop.OrderService#<clinit>()V";

    @TempDir
    Path temp;

    private final AtomicLong clock = new AtomicLong();
    private final List<CodeInventoryService> services = new ArrayList<>();
    private final Set<String> hiddenPanels = ConcurrentHashMap.newKeySet();
    private final AgentEvidence evidence = new AgentEvidence(panel -> !hiddenPanels.contains(panel), null);
    private final CodeInventoryHistory history = new CodeInventoryHistory(null, new ScanCache(1000));

    @BeforeEach
    void installAgent() {
        resetBridge();
        AgentBridge.install(new StubAgent());
    }

    @AfterEach
    void resetAgent() {
        services.forEach(CodeInventoryService::close);
        resetBridge();
    }

    private static void resetBridge() {
        try {
            Method reset = AgentBridge.class.getDeclaredMethod("reset");
            reset.setAccessible(true);
            reset.invoke(null);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Answers the agent's operations as an installed agent with an installed inventory sensor does. */
    static final class StubAgent implements Function<Map<String, Object>, Map<String, Object>> {
        @Override
        public Map<String, Object> apply(Map<String, Object> request) {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "ok");
            return answer;
        }
    }

    private static Map<String, byte[]> sources(String greeting) {
        return Compiler.compile(Map.of("shop.OrderService", """
                        package shop;
                        public class OrderService {
                            static final java.util.List<String> NAMES = new java.util.ArrayList<>();
                            public String greet(String name) { return "%s" + name; }
                            public int total(int a, int b) { return a + b; }
                            public void neverCalled() { NAMES.clear(); }
                        }
                        """.formatted(greeting)), "-g");
    }

    private Path classes() throws Exception {
        Path root = temp.resolve("app/target/classes");
        Compiler.write(root, sources("Hello, "));
        return root;
    }

    private AgentClaim claim() {
        return AgentClaim.claim(
                AgentBridgeAccess.bind(AgentBridge.class), "shop", "shop-owner", "dev", List.of("shop"));
    }

    private CodeInventoryService service(AgentClaim claim, ClassLoader loader, List<DependencyDto> declared) {
        CodeInventoryService service = new CodeInventoryService(
                AgentBridgeAccess.bind(AgentBridge.class),
                () -> claim,
                () -> null,
                () -> loader,
                () -> DependencyInventory.complete(declared),
                () -> 1_000L,
                CodeInventorySettings.defaults(),
                history,
                evidence,
                clock::get);
        services.add(service);
        return service;
    }

    /** What the agent does when a class loads: an id per method with code but the static initializer, tracked. */
    private static int[] load(String... keys) {
        int[] ids = new int[keys.length];
        for (int i = 0; i < keys.length; i++) {
            ids[i] = CodeInventory.methodId(keys[i]);
        }
        CodeInventory.tracked(ids);
        return ids;
    }

    private void tick() {
        clock.addAndGet(CodeInventoryService.VIEW_TTL_NANOS + 1);
    }

    private static Map<String, CodeInventoryMethodDto> byKey(List<CodeInventoryMethodDto> methods) {
        Map<String, CodeInventoryMethodDto> map = new LinkedHashMap<>();
        methods.forEach(method -> map.put(method.key(), method));
        return map;
    }

    @Test
    void aReadAfterAMethodsFirstCallSeesItExecutedWithoutWaitingForTheViewToAge() throws Exception {
        Path root = classes();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            int[] ids = load(INIT, GREET, TOTAL, NEVER);
            CodeInventoryService service = service(claim, loader, List.of());
            service.start();
            service.awaitScan();
            CodeInventory.hit(ids[0]);
            assertThat(service.report().methods().executed()).isEqualTo(1);
            CodeInventoryReport unchanged = service.report();
            assertThat(unchanged.methods().executed())
                    .as("nothing ran since: the view is reused")
                    .isEqualTo(1);

            // The same clock instant: only the bridge's inventory version says the view is stale.
            CodeInventory.hit(ids[1]);

            assertThat(service.report().methods().executed()).isEqualTo(2);
            assertThat(byKey(service.methods("shop", null, null, null, null).methods())
                            .get(GREET)
                            .status())
                    .isEqualTo(CodeInventoryService.EXECUTED);
        }
    }

    @Test
    void countsExecutedAndNeverExecutedMethodsAmongTrackedOnes() throws Exception {
        Path root = classes();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            assertThat(claim.armed()).isTrue();
            int[] ids = load(INIT, GREET, TOTAL, NEVER);
            CodeInventoryService service = service(claim, loader, List.of());
            service.start();
            service.awaitScan();
            CodeInventory.hit(ids[0]);
            CodeInventory.hit(ids[1]);
            long request = Long.parseUnsignedLong("00000000000000ab", 16);
            AgentRing.publish(
                    AgentRing.SENSOR_INVENTORY,
                    CodeInventory.FIRST_HIT,
                    claim.generation(),
                    5_000L,
                    ids[1],
                    request,
                    AgentRing.intern("GET /orders"),
                    0L);

            CodeInventoryReport report = service.report();

            assertThat(report.available()).isTrue();
            assertThat(report.scan().status()).isEqualTo(ClassScanner.COMPLETE);
            assertThat(report.methods().methods()).isEqualTo(5);
            assertThat(report.methods().tracked()).isEqualTo(4);
            assertThat(report.methods().executed()).isEqualTo(2);
            assertThat(report.methods().neverExecuted()).isEqualTo(2);
            assertThat(report.methods().notTracked()).isEqualTo(1);
            assertThat(report.changes().previousRun()).isFalse();
            assertThat(report.changes().note()).isEqualTo(CodeInventoryService.NO_PREVIOUS_RUN);
            assertThat(report.limitations()).contains(CodeInventoryService.NOT_SEEN_BEFORE_CLAIM);

            CodeInventoryMethodsReport methods = service.methods("shop", null, null, null, null);
            Map<String, CodeInventoryMethodDto> rows = byKey(methods.methods());
            assertThat(rows.get(GREET).status()).isEqualTo(CodeInventoryService.EXECUTED);
            assertThat(rows.get(GREET).firstRequestId()).isEqualTo("00000000000000ab");
            assertThat(rows.get(GREET).firstRoute()).isEqualTo("GET /orders");
            assertThat(rows.get(NEVER).status()).isEqualTo(CodeInventoryService.NEVER_EXECUTED);
            assertThat(rows.get(CLINIT).status()).isEqualTo(CodeInventoryService.NOT_TRACKED);
            assertThat(rows.get(CLINIT).notTrackedReason()).isEqualTo(CodeInventoryService.STATIC_INITIALIZER);
            assertThat(methods.packages()).singleElement().satisfies(row -> {
                assertThat(row.name()).isEqualTo("shop");
                assertThat(row.executed()).isEqualTo(2);
                assertThat(row.neverExecuted()).isEqualTo(2);
            });
            assertThat(methods.classes())
                    .singleElement()
                    .satisfies(row -> assertThat(row.className()).isEqualTo("shop.OrderService"));
            assertThat(service.methods(null, "OrderService", "never-executed", null, null)
                            .methods())
                    .extracting(CodeInventoryMethodDto::key)
                    .containsExactly(TOTAL, NEVER);
        }
    }

    @Test
    void aFirstCallRecordedWithoutItsRouteIsNamedFromTheRequestsRoute() throws Exception {
        Path root = classes();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            int[] ids = load(INIT, GREET, TOTAL, NEVER);
            CodeInventoryService service = service(claim, loader, List.of());
            service.setRequestRoutes(requestIds -> requestIds.contains("00000000000000cd")
                    ? Map.of("00000000000000cd", "GET /orders/{id}")
                    : Map.of());
            service.start();
            service.awaitScan();
            CodeInventory.hit(ids[2]);
            // The route was not matched yet when the method first ran: the record carries the request alone.
            AgentRing.publish(
                    AgentRing.SENSOR_INVENTORY,
                    CodeInventory.FIRST_HIT,
                    claim.generation(),
                    6_000L,
                    ids[2],
                    Long.parseUnsignedLong("00000000000000cd", 16),
                    0L,
                    0L);

            CodeInventoryMethodDto total = byKey(
                            service.methods(null, null, "executed", null, null).methods())
                    .get(TOTAL);

            assertThat(total.firstRequestId()).isEqualTo("00000000000000cd");
            assertThat(total.firstRoute()).isEqualTo("GET /orders/{id}");
            assertThat(total.firstHitEpochMillis()).isEqualTo(6_000L);
        }
    }

    @Test
    void disablingHttpExchangesHidesFirstRequestsAndRoutesAlreadyRetained() throws Exception {
        Path root = classes();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            int[] ids = load(INIT, GREET, TOTAL, NEVER);
            CodeInventoryService service = service(claim, loader, List.of());
            AtomicInteger lookups = new AtomicInteger();
            service.setRequestRoutes(requestIds -> {
                lookups.incrementAndGet();
                return Map.of("00000000000000cd", "GET /orders/{id}");
            });
            service.start();
            service.awaitScan();
            CodeInventory.hit(ids[1]);
            CodeInventory.hit(ids[2]);
            AgentRing.publish(
                    AgentRing.SENSOR_INVENTORY,
                    CodeInventory.FIRST_HIT,
                    claim.generation(),
                    5_000L,
                    ids[1],
                    Long.parseUnsignedLong("00000000000000ab", 16),
                    AgentRing.intern("GET /orders"),
                    0L);
            AgentRing.publish(
                    AgentRing.SENSOR_INVENTORY,
                    CodeInventory.FIRST_HIT,
                    claim.generation(),
                    6_000L,
                    ids[2],
                    Long.parseUnsignedLong("00000000000000cd", 16),
                    0L,
                    0L);
            Map<String, CodeInventoryMethodDto> visible = rows(service);
            assertThat(visible.get(GREET).firstRoute()).isEqualTo("GET /orders");
            assertThat(visible.get(TOTAL).firstRoute()).isEqualTo("GET /orders/{id}");
            long fingerprint = service.changesFingerprint();
            int looked = lookups.get();

            hiddenPanels.add(BootUiPanels.HTTP_EXCHANGES);

            Map<String, CodeInventoryMethodDto> hidden = rows(service);
            assertThat(hidden.get(GREET).status()).isEqualTo(CodeInventoryService.EXECUTED);
            assertThat(hidden.get(GREET).firstHitEpochMillis()).isEqualTo(5_000L);
            assertThat(hidden.values()).allSatisfy(method -> {
                assertThat(method.firstRoute()).isNull();
                assertThat(method.firstRequestId()).isNull();
            });
            assertThat(service.dependencies(null, null, null).dependencies()).allSatisfy(row -> {
                assertThat(row.firstRoute()).isNull();
                assertThat(row.firstRequestId()).isNull();
            });
            assertThat(service.report().limitations()).contains(CodeInventoryService.ROUTES_HIDDEN);
            assertThat(service.changesFingerprint()).isNotEqualTo(fingerprint);
            assertThat(lookups.get()).as("no journal lookup while hidden").isEqualTo(looked);

            hiddenPanels.remove(BootUiPanels.HTTP_EXCHANGES);

            assertThat(rows(service).get(TOTAL).firstRoute()).isEqualTo("GET /orders/{id}");
            assertThat(service.report().limitations()).doesNotContain(CodeInventoryService.ROUTES_HIDDEN);
        }
    }

    @Test
    void disablingCodeInventoryHidesEveryReadWithItsReasonAfterTheAgentsOwn() throws Exception {
        Path root = classes();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            load(INIT, GREET, TOTAL, NEVER);
            CodeInventoryService service = started(claim, loader);
            assertThat(service.report().available()).isTrue();
            long fingerprint = service.changesFingerprint();

            hiddenPanels.add(BootUiPanels.CODE_INVENTORY);

            assertThat(service.report().unavailableReason()).isEqualTo("The Code Inventory panel is disabled.");
            assertThat(service.methods(null, null, null, 0, 10).available()).isFalse();
            assertThat(service.changes(0, 10).available()).isFalse();
            assertThat(service.dependencies(null, 0, 10).available()).isFalse();
            assertThat(service.agentReport(null, 5).methods()).isEmpty();
            assertThat(service.changedCode().unavailableReason()).isEqualTo("The Code Inventory panel is disabled.");
            assertThat(service.changesFingerprint()).isNotEqualTo(fingerprint);
            assertThat(evidence.status().stores()).singleElement().satisfies(store -> {
                assertThat(store.visible()).isFalse();
                assertThat(store.retainedBytes()).isNull();
                assertThat(store.counts()).isEmpty();
            });

            hiddenPanels.remove(BootUiPanels.CODE_INVENTORY);
            assertThat(service.report().available()).isTrue();
        }
    }

    @Test
    void clearingTheRecordingDropsFirstRequestsAndRoutesButKeepsWhatExecuted() throws Exception {
        Path root = classes();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            int[] ids = load(INIT, GREET, TOTAL, NEVER);
            CodeInventoryService service = started(claim, loader);
            CodeInventory.hit(ids[1]);
            AgentRing.publish(
                    AgentRing.SENSOR_INVENTORY,
                    CodeInventory.FIRST_HIT,
                    claim.generation(),
                    5_000L,
                    ids[1],
                    Long.parseUnsignedLong("00000000000000ab", 16),
                    AgentRing.intern("GET /orders"),
                    0L);
            assertThat(rows(service).get(GREET).firstRequestId()).isEqualTo("00000000000000ab");
            assertThat(evidence.status().stores()).singleElement().satisfies(store -> {
                assertThat(store.visible()).isTrue();
                assertThat(store.retainedBytes()).isPositive();
                assertThat(store.counts()).containsEntry("firstCalls", 1L).containsEntry("firstCallsWithRequest", 1L);
            });
            long fingerprint = service.changesFingerprint();
            // Recorded before the clear, still queued in the ring when it runs.
            CodeInventory.hit(ids[2]);
            AgentRing.publish(
                    AgentRing.SENSOR_INVENTORY,
                    CodeInventory.FIRST_HIT,
                    claim.generation(),
                    6_000L,
                    ids[2],
                    Long.parseUnsignedLong("00000000000000cd", 16),
                    AgentRing.intern("GET /orders/{id}"),
                    0L);

            assertThat(evidence.clear()).isEqualTo("1 first request of Code Inventory");

            Map<String, CodeInventoryMethodDto> cleared = rows(service);
            assertThat(cleared.get(GREET).status()).isEqualTo(CodeInventoryService.EXECUTED);
            assertThat(cleared.get(GREET).firstHitEpochMillis()).isEqualTo(5_000L);
            assertThat(cleared.get(TOTAL).status()).isEqualTo(CodeInventoryService.EXECUTED);
            assertThat(cleared.get(TOTAL).firstHitEpochMillis()).isEqualTo(6_000L);
            assertThat(cleared.values()).allSatisfy(method -> {
                assertThat(method.firstRequestId()).isNull();
                assertThat(method.firstRoute()).isNull();
            });
            CodeInventoryReport report = service.report();
            assertThat(report.recordingClearedAt()).isNotNull();
            assertThat(report.limitations()).contains(CodeInventoryService.RECORDING_CLEARED);
            assertThat(service.changesFingerprint()).isNotEqualTo(fingerprint);

            // Recorded after the clear: kept whole.
            CodeInventory.hit(ids[3]);
            AgentRing.publish(
                    AgentRing.SENSOR_INVENTORY,
                    CodeInventory.FIRST_HIT,
                    claim.generation(),
                    System.currentTimeMillis() + 60_000L,
                    ids[3],
                    Long.parseUnsignedLong("00000000000000ef", 16),
                    AgentRing.intern("GET /later"),
                    0L);
            tick();
            CodeInventoryMethodDto later = rows(service).get(NEVER);
            assertThat(later.firstRequestId()).isEqualTo("00000000000000ef");
            assertThat(later.firstRoute()).isEqualTo("GET /later");
        }
    }

    @Test
    void aRequestsRouteIsRememberedForItsRecordsDrainedInALaterBatch() throws Exception {
        Path root = classes();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            int[] ids = load(INIT, GREET, TOTAL, NEVER);
            CodeInventoryService service = started(claim, loader);
            CodeInventory.hit(ids[1]);
            AgentRing.publish(
                    AgentRing.SENSOR_INVENTORY,
                    CodeInventory.FIRST_HIT,
                    claim.generation(),
                    5_000L,
                    ids[1],
                    Long.parseUnsignedLong("00000000000000ab", 16),
                    AgentRing.intern("GET /orders"),
                    0L);
            assertThat(rows(service).get(GREET).firstRoute()).isEqualTo("GET /orders");
            // The same request's next first call, drained later, carries no route: the framework matched it already.
            CodeInventory.hit(ids[2]);
            AgentRing.publish(
                    AgentRing.SENSOR_INVENTORY,
                    CodeInventory.FIRST_HIT,
                    claim.generation(),
                    5_001L,
                    ids[2],
                    Long.parseUnsignedLong("00000000000000ab", 16),
                    0L,
                    0L);
            tick();

            assertThat(rows(service).get(TOTAL).firstRoute()).isEqualTo("GET /orders");
        }
    }

    @Test
    void lateAndFailedMethodsAreNotTrackedAndAClassNeverLoadedIsNeverExecuted() throws Exception {
        Path root = classes();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            int greet = CodeInventory.methodId(GREET);
            int total = CodeInventory.methodId(TOTAL);
            CodeInventory.tracked(new int[] {greet, total}, true);
            CodeInventory.transformFailed(new int[] {CodeInventory.methodId(NEVER)});
            CodeInventory.hit(total);
            CodeInventoryService service = service(claim, loader, List.of());
            service.start();
            service.awaitScan();

            Map<String, CodeInventoryMethodDto> rows =
                    byKey(service.methods(null, null, null, null, null).methods());

            assertThat(rows.get(GREET).notTrackedReason()).isEqualTo(CodeInventoryService.RAN_BEFORE_INSTRUMENTATION);
            assertThat(rows.get(TOTAL).status()).isEqualTo(CodeInventoryService.EXECUTED);
            assertThat(rows.get(NEVER).notTrackedReason()).isEqualTo(CodeInventoryService.TRANSFORM_FAILED);
            // The constructor got no id while its class has others: its class was instrumented without it.
            assertThat(rows.get(INIT).status()).isEqualTo(CodeInventoryService.NOT_TRACKED);
        }
    }

    @Test
    void twoRunsListExactlyTheEditedMethodAsChangedExecutedOrNotByTheBitset() throws Exception {
        Path root = classes();
        // Run 1: everything executed.
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim first = claim();
            int[] ids = load(INIT, GREET, TOTAL, NEVER);
            CodeInventoryService service = service(first, loader, List.of());
            service.start();
            service.awaitScan();
            for (int id : ids) {
                CodeInventory.hit(id);
            }
            assertThat(service.changes(null, null).changes()).isEmpty();
            service.close();
            first.disarm();
        }
        // The developer edits greet; DevTools restarts: a new claim of the same application, a new context.
        Path file = root.resolve("shop/OrderService.class");
        Files.write(file, sources("Hi, ").get("shop.OrderService"));
        Files.setLastModifiedTime(
                file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim second = claim();
            assertThat(second.generation()).isGreaterThan(1L);
            int[] ids = load(INIT, GREET, TOTAL, NEVER);
            CodeInventory.hit(ids[2]);
            CodeInventoryService service = service(second, loader, List.of());
            service.start();
            service.awaitScan();

            CodeInventoryChangesReport changes = service.changes(null, null);

            assertThat(changes.counts().previousRun()).isTrue();
            assertThat(changes.counts().changed()).isEqualTo(1);
            assertThat(changes.counts().added()).isZero();
            assertThat(changes.counts().removed()).isZero();
            assertThat(changes.changes()).singleElement().satisfies(method -> {
                assertThat(method.key()).isEqualTo(GREET);
                assertThat(method.change()).isEqualTo(CodeChanges.CHANGED);
                assertThat(method.status()).isEqualTo(CodeInventoryService.NEVER_EXECUTED);
            });
            assertThat(changes.counts().notExecuted()).isEqualTo(1);

            CodeInventoryService.ChangedCode changed = service.changedCode();
            assertThat(changed.previousRun()).isTrue();
            assertThat(changed.classes())
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.className()).isEqualTo("shop.OrderService"));
            long before = changed.fingerprint();
            assertThat(changed.note()).isNull();
            hiddenPanels.add(BootUiPanels.HTTP_EXCHANGES);
            assertThat(service.changedCode().note())
                    .as("changed-code-not-executed names why its routes are left out")
                    .isEqualTo(CodeInventoryService.ROUTES_HIDDEN);
            hiddenPanels.remove(BootUiPanels.HTTP_EXCHANGES);

            CodeInventory.hit(ids[1]);
            tick();

            assertThat(service.changes(null, null).changes())
                    .singleElement()
                    .satisfies(method -> assertThat(method.status()).isEqualTo(CodeInventoryService.EXECUTED));
            assertThat(service.changedCode().fingerprint()).isNotEqualTo(before);
            CodeInventoryAgentReport agent = service.agentReport(null, 5);
            assertThat(agent.view()).isEqualTo("changed");
            assertThat(agent.methods()).extracting(CodeInventoryMethodDto::key).containsExactly(GREET);
            for (String query : List.of("greet", "OrderService#greet", "shop.OrderService#greet", "greet(Ljava")) {
                assertThat(service.agentReport(query, 5).methods())
                        .as("a method named by %s", query)
                        .extracting(CodeInventoryMethodDto::key)
                        .containsExactly(GREET);
            }
            assertThat(service.agentReport("Other#greet", 5).methods())
                    .as("another class's method of that name")
                    .isEmpty();
            assertThat(service.agentReport("gree", 5).methods())
                    .as("a method name is matched whole")
                    .isEmpty();
        }
    }

    private Map<String, CodeInventoryMethodDto> rows(CodeInventoryService service) {
        return byKey(service.methods(null, null, null, null, null).methods());
    }

    private CodeInventoryService started(AgentClaim claim, ClassLoader loader) throws InterruptedException {
        CodeInventoryService service = service(claim, loader, List.of());
        service.start();
        service.awaitScan();
        return service;
    }

    @Test
    void aClassTheAgentExcludesByNameIsNotTrackedRatherThanNeverExecuted() throws Exception {
        Path root = classes();
        Compiler.write(
                root,
                Compiler.compile(Map.of(
                        "shop.Cart_Subclass", "package shop; public class Cart_Subclass { public void add() {} }")));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            load(INIT, GREET, TOTAL, NEVER);
            CodeInventoryService service = started(claim, loader);

            Map<String, CodeInventoryMethodDto> rows = rows(service);

            assertThat(rows.get("shop.Cart_Subclass#add()V").status()).isEqualTo(CodeInventoryService.NOT_TRACKED);
            assertThat(rows.get("shop.Cart_Subclass#add()V").notTrackedReason())
                    .isEqualTo(CodeInventoryService.EXCLUDED_CLASS);
            assertThat(rows.get(NEVER).status()).isEqualTo(CodeInventoryService.NEVER_EXECUTED);
        }
    }

    @Test
    void aClassPastTheMethodLimitOrThatFailedBeforeAnyMethodGotAnIdIsNotTracked() throws Exception {
        Path root = classes();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            CodeInventory.overLimit("shop.OrderService");
            CodeInventoryService service = started(claim, loader);

            assertThat(rows(service).get(NEVER).notTrackedReason()).isEqualTo(CodeInventoryService.OVER_THE_LIMIT);
            assertThat(service.report().methods().tracked()).isZero();
        }
        resetBridge();
        AgentBridge.install(new StubAgent());
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            CodeInventory.transformFailed("shop.OrderService", new int[0]);
            CodeInventoryService service = started(claim, loader);

            Map<String, CodeInventoryMethodDto> rows = rows(service);
            assertThat(rows.get(GREET).notTrackedReason()).isEqualTo(CodeInventoryService.TRANSFORM_FAILED);
            assertThat(rows.get(NEVER).notTrackedReason()).isEqualTo(CodeInventoryService.TRANSFORM_FAILED);
        }
    }

    @Test
    void aFailureTheAgentCouldNotAttributeLeavesClassesWithoutIdsUnknownNotNeverExecuted() throws Exception {
        Path root = classes();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            CodeInventory.transformFailed(null, new int[0]);
            CodeInventoryService service = started(claim, loader);

            CodeInventoryMethodDto never = rows(service).get(NEVER);
            assertThat(never.status()).isEqualTo(CodeInventoryService.NOT_TRACKED);
            assertThat(never.notTrackedReason()).isEqualTo(CodeInventoryService.UNKNOWN_TRACKING);
        }
    }

    @Test
    void definitionOverflowIsNotTrackedAcrossRunsEvenIfTheMethodWasTrackedEarlier() throws Exception {
        Path root = classes();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim first = claim();
            int[] ids = load(INIT, GREET, TOTAL, NEVER);
            CodeInventory.hit(ids[1]);
            CodeInventory.tracked("shop.OrderService", ids, false, -1);
            CodeInventory.tracked("shop.OrderService", ids, false, 0);
            CodeInventoryService service = started(first, loader);

            assertThat(rows(service).get(GREET).status()).isEqualTo(CodeInventoryService.EXECUTED);
            assertThat(rows(service).get(NEVER).status()).isEqualTo(CodeInventoryService.NOT_TRACKED);
            assertThat(rows(service).get(NEVER).notTrackedReason()).isEqualTo(CodeInventoryService.DEFINITION_LIMIT);
            assertThat(service.report().methods().neverExecuted()).isZero();
            assertThat(service.report().limitations()).anyMatch(reason -> reason.contains("defining-loader capacity"));

            first.disarm();
            CodeInventoryService next = started(claim(), loader);
            assertThat(rows(next).get(GREET).status()).isEqualTo(CodeInventoryService.NOT_TRACKED);
            assertThat(rows(next).get(NEVER).notTrackedReason()).isEqualTo(CodeInventoryService.DEFINITION_LIMIT);

            CodeInventory.tracked(ids);
            tick();
            assertThat(rows(next).get(NEVER).status()).isEqualTo(CodeInventoryService.NEVER_EXECUTED);
        }
    }

    @Test
    void aCurrentDefinitionLimitMakesUnprovenMethodsUnknownRatherThanNeverExecuted() throws Exception {
        Path root = classes();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            CodeInventory.definitionLimitReached();
            CodeInventoryService service = started(claim, loader);

            assertThat(rows(service).get(NEVER).status()).isEqualTo(CodeInventoryService.NOT_TRACKED);
            assertThat(rows(service).get(NEVER).notTrackedReason()).isEqualTo(CodeInventoryService.DEFINITION_LIMIT);
        }
    }

    @Test
    void aDefinitionLimitDoesNotInvalidateCurrentPositiveTrackingEvidenceAndRefreshesTheView() throws Exception {
        Path root = classes();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            CodeInventoryService service = started(claim, loader);
            assertThat(rows(service).get(NEVER).status()).isEqualTo(CodeInventoryService.NEVER_EXECUTED);
            CodeInventory.definitionLimitReached();
            assertThat(rows(service).get(NEVER).status()).isEqualTo(CodeInventoryService.NOT_TRACKED);
            load(GREET);
            assertThat(rows(service).get(GREET).status()).isEqualTo(CodeInventoryService.NEVER_EXECUTED);
        }
    }

    @Test
    void aClassTrackedInAnEarlierRunWhoseTransformationFailsInThisOneIsNotTracked() throws Exception {
        Path root = classes();
        int[] ids;
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim first = claim();
            ids = load(INIT, GREET, TOTAL, NEVER);
            CodeInventoryService service = started(first, loader);
            assertThat(rows(service).get(NEVER).status()).isEqualTo(CodeInventoryService.NEVER_EXECUTED);
            service.close();
            first.disarm();
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim second = claim();
            // The new class loader's class fails before any method matched: the agent fails the ids it had.
            CodeInventory.transformFailed("shop.OrderService", ids);
            CodeInventoryService service = started(second, loader);

            Map<String, CodeInventoryMethodDto> rows = rows(service);
            assertThat(rows.get(NEVER).notTrackedReason()).isEqualTo(CodeInventoryService.TRANSFORM_FAILED);
            assertThat(rows.get(GREET).notTrackedReason()).isEqualTo(CodeInventoryService.TRANSFORM_FAILED);
            assertThat(service.report().methods().tracked()).isZero();
        }
    }

    @Test
    void anAddedMethodOfAKnownClassNotLoadedYetInThisRunIsNeverExecutedAndReported() throws Exception {
        Path root = classes();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim first = claim();
            load(INIT, GREET, TOTAL, NEVER);
            CodeInventoryService service = started(first, loader);
            service.close();
            first.disarm();
        }
        Path file = root.resolve("shop/OrderService.class");
        Files.write(
                file, Compiler.compile(Map.of("shop.OrderService", """
                        package shop;
                        public class OrderService {
                            static final java.util.List<String> NAMES = new java.util.ArrayList<>();
                            public String greet(String name) { return "Hello, " + name; }
                            public int total(int a, int b) { return a + b; }
                            public void neverCalled() { NAMES.clear(); }
                            public void refund() { NAMES.add("refund"); }
                        }
                        """), "-g").get("shop.OrderService"));
        Files.setLastModifiedTime(
                file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            // A restart: OrderService has not loaded in the new class loader yet.
            AgentClaim second = claim();
            CodeInventoryService service = started(second, loader);

            Map<String, CodeInventoryMethodDto> rows = rows(service);
            assertThat(rows.get("shop.OrderService#refund()V").status()).isEqualTo(CodeInventoryService.NEVER_EXECUTED);
            assertThat(rows.get(GREET).status()).isEqualTo(CodeInventoryService.NEVER_EXECUTED);
            assertThat(service.changedCode().classes())
                    .singleElement()
                    .satisfies(changed -> assertThat(changed.methods())
                            .extracting(CodeInventoryMethodDto::key)
                            .containsExactly("shop.OrderService#refund()V"));
            assertThat(service.changes(null, null).counts().notExecuted()).isEqualTo(1);
        }
    }

    @Test
    void anAbstractMethodIsListedAsNotTracked() throws Exception {
        Path root = classes();
        Compiler.write(
                root,
                Compiler.compile(Map.of(
                        "shop.OrderRepository",
                        "package shop; public interface OrderRepository { java.util.List<String> findAll(); }")));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            CodeInventoryService service = started(claim, loader);

            CodeInventoryMethodDto findAll = rows(service).get("shop.OrderRepository#findAll()Ljava/util/List;");
            assertThat(findAll.status()).isEqualTo(CodeInventoryService.NOT_TRACKED);
            assertThat(findAll.notTrackedReason()).isEqualTo(CodeInventoryService.ABSTRACT);
        }
    }

    /** A class loader whose package listing waits for {@code gate}, or throws {@code failure} when given. */
    private static URLClassLoader gated(Path root, java.util.concurrent.CountDownLatch gate, Error failure)
            throws Exception {
        return new URLClassLoader(new URL[] {root.toUri().toURL()}, null) {
            @Override
            public java.util.Enumeration<URL> getResources(String name) throws java.io.IOException {
                if (failure != null) {
                    throw failure;
                }
                try {
                    gate.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return super.getResources(name);
            }
        };
    }

    private void keepPreviousRun(Path root) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim first = claim();
            CodeInventoryService service = started(first, loader);
            service.close();
            first.disarm();
        }
    }

    @Test
    void whileTheScanRunsTheChangesSaySoAndKeepThePreviousRun() throws Exception {
        Path root = classes();
        keepPreviousRun(root);
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        try (URLClassLoader loader = gated(root, gate, null)) {
            AgentClaim second = claim();
            load(INIT, GREET, TOTAL, NEVER);
            CodeInventoryService service = service(second, loader, List.of());
            service.start();

            CodeInventoryChangesReport running = service.changes(null, null);
            assertThat(running.counts().scanStatus()).isEqualTo(CodeInventoryService.RUNNING);
            assertThat(running.counts().previousRun())
                    .as("known before the scan ends")
                    .isTrue();
            assertThat(running.counts().note()).isEqualTo(CodeInventoryService.SCAN_RUNNING);
            CodeInventoryService.ChangedCode changed = service.changedCode();
            assertThat(changed.scanInProgress()).isTrue();
            assertThat(changed.previousRun()).isTrue();

            gate.countDown();
            service.awaitScan();
            tick();
            assertThat(service.changes(null, null).counts().scanStatus()).isEqualTo(ClassScanner.COMPLETE);
            assertThat(service.changedCode().scanInProgress()).isFalse();
        }
    }

    @Test
    void aFailedScanIsNeverComparedNorKeptAndSaysWhyWithoutItsMessage() throws Exception {
        Path root = classes();
        keepPreviousRun(root);
        long kept = history.version();
        try (URLClassLoader loader = gated(root, null, new AssertionError("/Users/secret/path"))) {
            AgentClaim second = claim();
            CodeInventoryService service = started(second, loader);

            CodeInventoryReport report = service.report();
            assertThat(report.scan().status()).isEqualTo(ClassScanner.FAILED);
            assertThat(report.scan().reason())
                    .isEqualTo("The scan failed: AssertionError.")
                    .doesNotContain("/Users/secret");
            CodeInventoryChangesReport changes = service.changes(null, null);
            assertThat(changes.counts().scanStatus()).isEqualTo(ClassScanner.FAILED);
            assertThat(changes.counts().previousRun()).isTrue();
            assertThat(changes.counts().note()).startsWith(CodeInventoryService.SCAN_FAILED);
            assertThat(changes.counts().removed()).isNull();
            CodeInventoryService.ChangedCode changed = service.changedCode();
            assertThat(changed.scanFailed()).isTrue();
            assertThat(changed.scanReason()).isEqualTo("The scan failed: AssertionError.");
            assertThat(history.version()).as("the failed run is not kept").isEqualTo(kept);
        }
    }

    @Test
    void aScanCancelledWithItsRunIsNotKept() throws Exception {
        Path root = classes();
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        try (URLClassLoader loader = gated(root, gate, null)) {
            AgentClaim claim = claim();
            CodeInventoryService service = service(claim, loader, List.of());
            service.start();
            Thread scan = service.scanThread();
            service.close();
            gate.countDown();
            scan.join(60_000);
            assertThat(scan.isAlive()).isFalse();

            assertThat(history.version()).isZero();
            assertThat(history.previous(claim.slot(), claim.generation() + 1)).isNull();
        }
    }

    @Test
    void aRequestsRouteIsLookedUpOnceNotOnEveryRead() throws Exception {
        Path root = classes();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            int[] ids = load(INIT, GREET, TOTAL, NEVER);
            CodeInventoryService service = service(claim, loader, List.of());
            List<java.util.Set<String>> lookups = new ArrayList<>();
            service.setRequestRoutes(requestIds -> {
                lookups.add(requestIds);
                return requestIds.contains("00000000000000cd")
                        ? Map.of("00000000000000cd", "GET /orders/{id}")
                        : Map.of();
            });
            service.start();
            service.awaitScan();
            for (int i = 1; i <= 2; i++) {
                CodeInventory.hit(ids[i]);
            }
            AgentRing.publish(
                    AgentRing.SENSOR_INVENTORY,
                    CodeInventory.FIRST_HIT,
                    claim.generation(),
                    6_000L,
                    ids[1],
                    Long.parseUnsignedLong("00000000000000cd", 16),
                    0L,
                    0L);
            AgentRing.publish(
                    AgentRing.SENSOR_INVENTORY,
                    CodeInventory.FIRST_HIT,
                    claim.generation(),
                    6_001L,
                    ids[2],
                    Long.parseUnsignedLong("00000000000000ef", 16),
                    0L,
                    0L);

            assertThat(rows(service).get(GREET).firstRoute()).isEqualTo("GET /orders/{id}");
            assertThat(lookups)
                    .singleElement()
                    .satisfies(
                            ids2 -> assertThat(ids2).containsExactlyInAnyOrder("00000000000000cd", "00000000000000ef"));
            tick();
            assertThat(rows(service).get(GREET).firstRoute()).isEqualTo("GET /orders/{id}");
            assertThat(lookups).hasSize(2);
            assertThat(lookups.get(1))
                    .as("only the request not named yet, still in flight")
                    .containsExactly("00000000000000ef");
            for (int i = 0; i < InventoryRecords.MAX_LOOKUPS + 2; i++) {
                tick();
                rows(service);
            }
            assertThat(lookups).as("given up after a bounded number of tries").hasSize(InventoryRecords.MAX_LOOKUPS);
        }
    }

    @Test
    void theCheapFingerprintMovesWithANewCallButBuildsNoView() throws Exception {
        Path root = classes();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            int[] ids = load(INIT, GREET, TOTAL, NEVER);
            CodeInventoryService service = service(claim, loader, List.of());
            java.util.concurrent.atomic.AtomicInteger builds = new java.util.concurrent.atomic.AtomicInteger();
            service.setRequestRoutes(requestIds -> {
                builds.incrementAndGet();
                return Map.of();
            });
            service.start();
            service.awaitScan();
            long before = service.changesFingerprint();
            assertThat(service.changesFingerprint()).isEqualTo(before);

            CodeInventory.hit(ids[1]);

            assertThat(service.changesFingerprint()).isNotEqualTo(before);
            assertThat(builds.get())
                    .as("no view was built, so no route was looked up")
                    .isZero();
        }
    }

    @Test
    void concurrentReadsShareOneBuild() throws Exception {
        Path root = classes();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            int[] ids = load(INIT, GREET, TOTAL, NEVER);
            CodeInventoryService service = service(claim, loader, List.of());
            java.util.concurrent.atomic.AtomicInteger builds = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.CountDownLatch inBuild = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            service.setRequestRoutes(requestIds -> {
                builds.incrementAndGet();
                inBuild.countDown();
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return Map.of();
            });
            service.start();
            service.awaitScan();
            CodeInventory.hit(ids[1]);
            AgentRing.publish(
                    AgentRing.SENSOR_INVENTORY,
                    CodeInventory.FIRST_HIT,
                    claim.generation(),
                    6_000L,
                    ids[1],
                    Long.parseUnsignedLong("00000000000000cd", 16),
                    0L,
                    0L);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
            try {
                List<java.util.concurrent.Future<CodeInventoryReport>> reads = new ArrayList<>();
                reads.add(pool.submit(service::report));
                inBuild.await();
                for (int i = 0; i < 3; i++) {
                    reads.add(pool.submit(service::report));
                }
                Thread.sleep(100);
                release.countDown();
                for (java.util.concurrent.Future<CodeInventoryReport> read : reads) {
                    assertThat(read.get().methods().executed()).isEqualTo(1);
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(builds.get())
                    .as("the waiting readers reused the fresh view")
                    .isEqualTo(1);
        }
    }

    @Test
    void dependencyUseMatchesDeclaredJarsAndSaysWhichNeverLoaded() throws Exception {
        Path root = classes();
        Path lib = temp.resolve("repo/commons-tiny-1.0.jar");
        Path unused = temp.resolve("repo/never-used-2.0.jar");
        jar(lib, "org.tiny", "commons-tiny", "1.0");
        jar(unused, "org.never", "never-used", "2.0");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            CodeInventoryService service = service(
                    claim,
                    loader,
                    List.of(
                            dependency("org.tiny", "commons-tiny", "1.0"),
                            dependency("org.never", "never-used", "2.0")));
            service.start();
            service.awaitScan();
            CodeInventory.classLoaded(lib.toUri().toURL().toString(), "org/tiny/Tiny");
            CodeInventory.classLoaded(root.toUri().toURL().toString(), "shop/OrderService");
            CodeInventory.classLoaded(
                    temp.resolve("repo/bootui-engine-9.jar").toUri().toURL().toString(), "x/Y");

            List<CodeInventoryDependencyDto> rows =
                    service.dependencies(null, null, null).dependencies();

            assertThat(rows)
                    .extracting(CodeInventoryDependencyDto::artifactId, CodeInventoryDependencyDto::status)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("never-used", CodeInventoryService.NOT_LOADED),
                            org.assertj.core.groups.Tuple.tuple("commons-tiny", CodeInventoryService.LOADED));
            CodeInventoryDependencyDto tiny = rows.get(1);
            assertThat(tiny.jar()).isEqualTo("commons-tiny-1.0.jar");
            assertThat(tiny.classesLoaded()).isEqualTo(1);
            assertThat(tiny.loadedAt()).isEqualTo(CodeInventoryService.AFTER_STARTUP);
            assertThat(service.report().dependencies().notLoaded()).isEqualTo(1);
            assertThat(service.agentReport("dependencies", 1).dependencies()).hasSize(1);
        }
    }

    @Test
    void theScanReadsTheDeclaredDependenciesOnceBeforeAnyRead() throws Exception {
        Path root = classes();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            load(INIT, GREET, TOTAL, NEVER);
            List<String> readers = new java.util.concurrent.CopyOnWriteArrayList<>();
            CodeInventoryService service = new CodeInventoryService(
                    AgentBridgeAccess.bind(AgentBridge.class),
                    () -> claim,
                    () -> null,
                    () -> loader,
                    () -> {
                        readers.add(Thread.currentThread().getName());
                        return DependencyInventory.complete(List.of());
                    },
                    () -> 1_000L,
                    CodeInventorySettings.defaults(),
                    history,
                    evidence,
                    clock::get);
            services.add(service);
            service.start();
            service.awaitScan();

            assertThat(readers)
                    .as("read off the request thread, as the scan ends")
                    .containsExactly("bootui-code-inventory-scan");
            assertThat(service.agentReport(null, null).summary().available()).isTrue();
            assertThat(readers).as("a read reuses it").hasSize(1);
        }
    }

    @Test
    void isUnavailableWithTheAgentsReason() {
        CodeInventoryService service = new CodeInventoryService(
                AgentBridgeAccess.absent(),
                () -> null,
                () -> "Requires the BootUI agent's inventory sensor: not attached.",
                null,
                null,
                null,
                null,
                history,
                AgentEvidence.open());
        services.add(service);

        assertThat(service.report().available()).isFalse();
        assertThat(service.report().unavailableReason()).startsWith("Requires the BootUI agent's inventory sensor");
        assertThat(service.changes(0, 10).available()).isFalse();
        assertThat(service.methods(null, null, null, 0, 10).available()).isFalse();
        assertThat(service.dependencies(null, 0, 10).available()).isFalse();
        assertThat(service.agentReport(null, null).summary().available()).isFalse();
        assertThat(service.changedCode().unavailableReason()).isNotNull();
    }

    @Test
    void theDrainerStopsWithTheService() throws Exception {
        Path root = classes();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            AgentClaim claim = claim();
            CodeInventoryService service = service(claim, loader, List.of());
            service.start();
            AgentRecordDrainer drainer = service.drainer();
            assertThat(drainer.running()).isTrue();

            service.close();

            assertThat(drainer.running()).isFalse();
            assertThat(Thread.getAllStackTraces().keySet())
                    .noneMatch(thread -> thread.getName().equals(AgentRecordDrainer.THREAD_NAME) && thread.isAlive());
        }
    }

    private static DependencyDto dependency(String group, String artifact, String version) {
        return new DependencyDto(group, artifact, version, group + ":" + artifact, "test", 0, null, List.of(), null);
    }

    private static void jar(Path jar, String group, String artifact, String version) throws Exception {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/maven/" + group + "/" + artifact + "/pom.properties"));
            out.write(("groupId=" + group + "\nartifactId=" + artifact + "\nversion=" + version + "\n").getBytes());
            out.closeEntry();
        }
    }
}
