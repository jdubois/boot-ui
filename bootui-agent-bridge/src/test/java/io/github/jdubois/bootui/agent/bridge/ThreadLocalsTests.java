package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The thread-locals sensor's bridge side (PLAN-v2 §5.16, M5-5f), with a scanner standing in for the agent's: the test
 * says which thread locals the calling thread's maps hold with a value, and the bridge's scopes snapshot and diff them.
 */
class ThreadLocalsTests {

    private static final String REQUEST = "00000000000000ab";
    private static final long REQUEST_BITS = 0xabL;

    /** Leftover candidates, each a thread local named by a constant that holds the secret value it would carry. */
    static final ThreadLocal<String> TENANT = new ThreadLocal<>();

    static final ThreadLocal<String> CLEARED = new ThreadLocal<>();
    static final ThreadLocal<String> BEFORE = new ThreadLocal<>();
    static final InheritableThreadLocal<String> INHERITED = new InheritableThreadLocal<>();
    static final ThreadLocal<String> CACHE = ThreadLocal.withInitial(() -> "cache");
    static final ThreadLocal<String> SUBCLASS = new ThreadLocal<>() {};

    private final List<Object> keep = new ArrayList<>();
    private final AtomicReference<Object[]> context = new AtomicReference<>();
    private final FakeScanner scanner = new FakeScanner();

    @BeforeEach
    void install() {
        AgentBridge.reset();
        AgentBridge.install(request -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "ok");
            return answer;
        });
        ThreadLocals.install(scanner, new FakeResolver());
    }

    @AfterEach
    void reset() {
        AgentBridge.reset();
    }

    @Test
    void aThreadLocalLeftSetInAnExplicitScopeIsRecordedOnceWithItsOwnerNeverItsValue() {
        long token = enabledClaim();
        context.set(owner(REQUEST));
        scanner.set(BEFORE, true);

        long scope = ThreadLocals.open();
        scanner.set(TENANT, true);
        scanner.set(CLEARED, true);
        scanner.set(CLEARED, false);
        ThreadLocals.close(scope);

        List<long[]> records = drain(token);
        assertThat(scope).isPositive();
        assertThat(records).hasSize(1);
        long[] record = records.get(0);
        assertThat(record[SideEffects.R_SENSOR]).isEqualTo(SideEffects.SENSOR_THREAD_LOCALS);
        assertThat(record[SideEffects.R_KIND]).isEqualTo(SideEffects.KIND_THREAD_LOCAL_LEFT_SET);
        assertThat(record[SideEffects.R_REQUEST]).isEqualTo(REQUEST_BITS);
        assertThat(record[SideEffects.R_NANOS]).isEqualTo(scanner.hash(TENANT) & 0xFFFFFFFFL);
        assertThat(string(record[SideEffects.R_TARGET])).isEqualTo("java.lang.ThreadLocal");
        assertThat(detail(record)).isZero();
        assertThat(id(record)).isPositive();
        assertThat(String.join("|", interned())).doesNotContain("secret");
    }

    @Test
    void aNullValueCountsAsClearedOnBothSides() {
        long token = enabledClaim();
        context.set(owner(REQUEST));
        scanner.set(TENANT, false);

        long scope = ThreadLocals.open();
        scanner.set(CLEARED, false);
        ThreadLocals.close(scope);
        assertThat(drain(token)).isEmpty();

        // Present with a null value at the open, then left set: a leak, not something set before.
        scope = ThreadLocals.open();
        scanner.set(TENANT, true);
        ThreadLocals.close(scope);
        assertThat(drain(token)).hasSize(1);
    }

    @Test
    void aValueSetBeforeTheScopeIsNeverReportedForIt() {
        long token = enabledClaim();
        context.set(owner(REQUEST));
        scanner.set(BEFORE, true);

        long scope = ThreadLocals.open();
        ThreadLocals.close(scope);

        assertThat(drain(token)).isEmpty();
    }

    @Test
    void detailsNameInheritableWithInitialAndSubclassThreadLocals() {
        long token = enabledClaim();
        context.set(owner(REQUEST));

        long scope = ThreadLocals.open();
        scanner.set(INHERITED, true, true);
        scanner.set(CACHE, true);
        scanner.set(SUBCLASS, true);
        ThreadLocals.close(scope);

        Map<String, Integer> details = new LinkedHashMap<>();
        for (long[] record : drain(token)) {
            details.put(string(record[SideEffects.R_TARGET]), detail(record));
        }
        assertThat(details)
                .containsExactly(
                        Map.entry("java.lang.InheritableThreadLocal", ThreadLocals.DETAIL_INHERITABLE),
                        Map.entry("java.lang.ThreadLocal$SuppliedThreadLocal", ThreadLocals.DETAIL_SUPPLIED));
        // This test's own subclass is in BootUI's agent package: skipped as BootUI's own, yet named a subclass.
        assertThat(ThreadLocals.counter("bootUiKeys")).isEqualTo(1L);
        assertThat(ThreadLocals.detail(SUBCLASS, false)).isEqualTo(ThreadLocals.DETAIL_SUBCLASS);
    }

    @Test
    void theBridgesOwnThreadLocalsAreNeverReported() {
        long token = enabledClaim();
        context.set(owner(REQUEST));

        long scope = ThreadLocals.open();
        scanner.set(CodePaths.FRAME, true);
        scanner.set(Reentrancy.STATE, true);
        scanner.set(TaskPropagation.ACTIVE, true);
        ThreadLocals.close(scope);

        assertThat(drain(token)).isEmpty();
        assertThat(ThreadLocals.counter("bridgeKeys")).isEqualTo(3L);
    }

    @Test
    void everyThreadLocalTheBridgeDeclaresIsSkippedByIdentity() throws Exception {
        java.nio.file.Path classes = java.nio.file.Path.of(ThreadLocals.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        java.nio.file.Path bridge = classes.resolve("io/github/jdubois/bootui/agent/bridge");
        List<String> declared = new ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(bridge)) {
            for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".class")) {
                    continue;
                }
                Class<?> type =
                        Class.forName("io.github.jdubois.bootui.agent.bridge." + name.substring(0, name.length() - 6));
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                            && ThreadLocal.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        declared.add(type.getSimpleName() + "." + field.getName());
                        assertThat(ThreadLocals.skipped(field.get(null)))
                                .as("%s.%s is skipped", type.getSimpleName(), field.getName())
                                .isTrue();
                    }
                }
            }
        }
        assertThat(declared).contains("CodePaths.FRAME", "Reentrancy.STATE", "CaughtExceptions.COUNTS");
    }

    @Test
    void bootUisModulesAreSkippedButNeverTheSampleApplications() {
        assertThat(ThreadLocals.bootUi("io.github.jdubois.bootui.engine.support.BootUiThreadLocal"))
                .isTrue();
        assertThat(ThreadLocals.bootUi("io.github.jdubois.bootui.agent.shaded.bytebuddy.Lock"))
                .isTrue();
        assertThat(ThreadLocals.bootUi("io.github.jdubois.bootui.sample.TenantContext"))
                .isFalse();
        assertThat(ThreadLocals.bootUi("io.github.jdubois.bootui.webfluxsample.TenantContext"))
                .isFalse();
        assertThat(ThreadLocals.bootUi("com.example.Holder")).isFalse();
    }

    @Test
    void aScopeWithoutAnOwnerOrWithTheSensorOffScansNothing() {
        long token = enabledClaim();

        assertThat(ThreadLocals.open()).isZero();
        assertThat(ThreadLocals.counter("unowned")).isEqualTo(1L);

        SideEffects.disable(SideEffects.MASK_THREAD_LOCALS, null);
        context.set(owner(REQUEST));
        assertThat(ThreadLocals.open()).isZero();
        assertThat(drain(token)).isEmpty();
        assertThat(scanner.snapshots).isZero();
    }

    @Test
    void aCloseOnAnotherThreadOrWithAnotherTokenDoesNothing() throws Exception {
        long token = enabledClaim();
        context.set(owner(REQUEST));

        long scope = ThreadLocals.open();
        scanner.set(TENANT, true);
        Thread other = new Thread(() -> ThreadLocals.close(scope));
        other.start();
        other.join();
        ThreadLocals.close(scope + 1);
        assertThat(ThreadLocals.counter("foreignCloses")).isEqualTo(2L);

        ThreadLocals.close(scope);
        assertThat(drain(token)).hasSize(1);
    }

    @Test
    void anExplicitScopeNeverClosedIsDroppedWhenAnotherRequestOpensOneWithoutAReport() {
        long token = enabledClaim();
        context.set(owner(REQUEST));

        ThreadLocals.open();
        scanner.set(TENANT, true);
        context.set(owner("00000000000000ac"));
        long next = ThreadLocals.open();
        ThreadLocals.close(next);

        assertThat(next).isPositive();
        assertThat(drain(token)).isEmpty();
        assertThat(ThreadLocals.counter("staleScopes")).isEqualTo(1L);
    }

    @Test
    void theSameRequestsWorkNestedInItsScopeNeverClosesIt() {
        long token = enabledClaim();
        context.set(owner(REQUEST));

        long outer = ThreadLocals.open();
        assertThat(ThreadLocals.open()).isZero();
        scanner.set(TENANT, true);
        ThreadLocals.close(outer);

        assertThat(drain(token)).hasSize(1);
        assertThat(ThreadLocals.counter("nested")).isEqualTo(1L);
    }

    @Test
    void anUnownedScopeReportsOnlyOnceSomethingOwnsItWhateverOrderItsThreadLocalsAreSetAndRestoredIn() {
        long token = enabledClaim();

        long scope = ThreadLocals.openUnowned();
        scanner.set(TENANT, true);
        ThreadLocals.close(scope);
        assertThat(drain(token)).isEmpty();
        assertThat(ThreadLocals.counter("unowned")).isEqualTo(1L);

        scanner.clear();
        scope = ThreadLocals.openUnowned();
        // A context propagation accessor sets its value before BootUI's names the owner, and restores it after.
        scanner.set(CLEARED, true);
        context.set(owner(REQUEST));
        ThreadLocals.own();
        context.set(null);
        scanner.set(TENANT, true);
        scanner.set(CLEARED, false);
        ThreadLocals.close(scope);
        List<long[]> records = drain(token);
        assertThat(records).hasSize(1);
        assertThat(records.get(0)[SideEffects.R_REQUEST]).isEqualTo(REQUEST_BITS);
        assertThat(records.get(0)[SideEffects.R_NANOS]).isEqualTo(scanner.hash(TENANT) & 0xFFFFFFFFL);
    }

    @Test
    void onlyTheOutermostScopeScans() {
        long token = enabledClaim();
        context.set(owner(REQUEST));

        long outer = ThreadLocals.open();
        SideEffects.handoff(new Object[] {REQUEST}, generation(), true);
        scanner.set(TENANT, true);
        SideEffects.handoffDone();
        assertThat(drain(token)).isEmpty();
        assertThat(ThreadLocals.counter("nested")).isEqualTo(1L);

        ThreadLocals.close(outer);
        assertThat(drain(token)).hasSize(1);
    }

    @Test
    void aPoolTaskOfARequestScansWhenItEndsAndAThreadTheApplicationStartedNever() {
        long token = enabledClaim();

        SideEffects.handoff(new Object[] {REQUEST}, generation(), true);
        scanner.set(TENANT, true);
        SideEffects.handoffDone();
        List<long[]> records = drain(token);
        assertThat(records).hasSize(1);
        assertThat(records.get(0)[SideEffects.R_REQUEST]).isEqualTo(REQUEST_BITS);

        scanner.clear();
        SideEffects.handoff(new Object[] {REQUEST}, generation(), false);
        scanner.set(TENANT, true);
        SideEffects.handoffDone();
        assertThat(drain(token)).isEmpty();
    }

    @Test
    void anAdapterScopeScansOnlyWhereTheStackAsksForIt() {
        long token = enabledClaim();
        context.set(owner(REQUEST));

        SideEffects.scopeBegin(null, false);
        scanner.set(TENANT, true);
        SideEffects.scopeEnd();
        assertThat(drain(token)).isEmpty();

        scanner.clear();
        ThreadLocals.configure(true);
        SideEffects.scopeBegin(null, false);
        scanner.set(TENANT, true);
        SideEffects.scopeEnd();
        List<long[]> records = drain(token);
        assertThat(records).hasSize(1);
        assertThat(records.get(0)[SideEffects.R_REQUEST]).isEqualTo(REQUEST_BITS);
    }

    @Test
    void aScopeReportsAtMostSixteenAndAnExcludedThreadLocalNeverTakesAPlace() {
        long token = enabledClaim();
        context.set(owner(REQUEST));
        List<ThreadLocal<String>> locals = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            locals.add(new ThreadLocal<>());
        }

        long scope = ThreadLocals.open();
        locals.forEach(local -> scanner.set(local, true));
        ThreadLocals.close(scope);
        List<long[]> first = drain(token);
        assertThat(first).hasSize(ThreadLocals.MAX_REPORTED);
        assertThat(ThreadLocals.counter("capped")).isEqualTo(4L);

        for (long[] record : first) {
            ThreadLocals.exclude(generation(), id(record), (int) record[SideEffects.R_NANOS]);
        }
        scanner.clear();
        scope = ThreadLocals.open();
        locals.forEach(local -> scanner.set(local, true));
        ThreadLocals.close(scope);
        assertThat(drain(token)).hasSize(4);
        assertThat(ThreadLocals.counter("excludedKeys")).isEqualTo(16L);
    }

    @Test
    void scopesTooLargeToScanAreSkipped() {
        long token = enabledClaim();
        context.set(owner(REQUEST));
        scanner.tooLarge = true;

        assertThat(ThreadLocals.open()).isZero();
        assertThat(ThreadLocals.counter("tooLarge")).isEqualTo(1L);
        assertThat(drain(token)).isEmpty();
    }

    @Test
    void aVirtualThreadIsNeverScanned() throws Exception {
        Thread thread;
        AtomicLong opened = new AtomicLong(-1);
        try {
            Object builder = Thread.class.getMethod("ofVirtual").invoke(null);
            thread = (Thread) Class.forName("java.lang.Thread$Builder")
                    .getMethod("unstarted", Runnable.class)
                    .invoke(builder, (Runnable) () -> opened.set(ThreadLocals.open()));
        } catch (ReflectiveOperationException ex) {
            // Before JDK 21: no virtual threads.
            return;
        }
        long token = enabledClaim();
        context.set(owner(REQUEST));
        thread.start();
        thread.join();

        assertThat(opened.get()).isZero();
        assertThat(ThreadLocals.counter("virtualSkipped")).isEqualTo(1L);
        assertThat(drain(token)).isEmpty();
    }

    @Test
    void theHolderIsResolvedThroughTheRegistryAndNeverForACollectedOrReusedSlot() {
        long token = enabledClaim();
        context.set(owner(REQUEST));
        long scope = ThreadLocals.open();
        scanner.set(TENANT, true);
        ThreadLocals.close(scope);
        long[] record = drain(token).get(0);
        int id = id(record);
        int hash = (int) record[SideEffects.R_NANOS];

        String[] holder = ThreadLocals.holder(generation(), id, hash, new String[] {"com.example"}, new String[0], 1L);
        assertThat(holder[0]).isEqualTo("resolved " + TENANT.getClass().getName());
        assertThat(ThreadLocals.holder(generation(), id, hash + 1, new String[0], new String[0], 1L)[3])
                .isEqualTo("collected");
        assertThat(ThreadLocals.holder(generation() + 1, id, hash, new String[0], new String[0], 1L)[3])
                .isEqualTo("collected");
    }

    @Test
    void theSelfTestScansWithoutAClaimOrAnOwnerAndRecordsNothing() {
        ThreadLocals.beginSelfTest();
        long scope = ThreadLocals.open();
        scanner.set(TENANT, true);
        scanner.set(CodePaths.FRAME, true);
        ThreadLocals.close(scope);
        int[] found = ThreadLocals.endSelfTest();

        assertThat(scope).isPositive();
        assertThat(found).containsExactly(scanner.hash(TENANT));
    }

    @Test
    void noKeyStaysInTheThreadsBuffersAfterAClose() {
        enabledClaim();
        context.set(owner(REQUEST));
        long scope = ThreadLocals.open();
        scanner.set(TENANT, true);
        ThreadLocals.close(scope);

        assertThat(Arrays.asList(CodePaths.FRAME.get().threadLocals.keys)).containsOnlyNulls();
    }

    @Test
    void theRegistryReusesTheSlotOfACollectedThreadLocal() {
        ThreadLocals.Registry registry = new ThreadLocals.Registry(1L);
        Object first = new Object();
        int id = ThreadLocals.id(registry, first, 7);
        registry.slots.get(id - 1).clear();

        Object second = new Object();
        assertThat(ThreadLocals.id(registry, second, 7)).isEqualTo(id);
        assertThat(ThreadLocals.id(registry, second, 7)).isEqualTo(id);
        assertThat(ThreadLocals.id(registry, new Object(), 7)).isEqualTo(id + 1);
    }

    @Test
    void sortedLookupsFindEveryValue() {
        int[] values = {5, -3, 9, 0, 9, Integer.MIN_VALUE, Integer.MAX_VALUE};
        ThreadLocals.sort(values, values.length);

        assertThat(values).isSorted();
        for (int value : values) {
            assertThat(ThreadLocals.contains(values, values.length, value)).isTrue();
        }
        assertThat(ThreadLocals.contains(values, values.length, 4)).isFalse();
        assertThat(ThreadLocals.hint("com.example.Holder$$Lambda/0x0000000801001234"))
                .isEqualTo("com.example.Holder");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** The calling thread's maps as the test sets them: thread locals with or without a value, and their map. */
    static final class FakeScanner extends ThreadLocals.Scanner {

        final Map<Thread, Map<Object, boolean[]>> maps = new IdentityHashMap<>();
        boolean tooLarge;
        int snapshots;

        void set(Object local, boolean value) {
            set(local, value, false);
        }

        void set(Object local, boolean value, boolean inheritable) {
            maps.computeIfAbsent(Thread.currentThread(), thread -> new LinkedHashMap<>())
                    .put(local, new boolean[] {value, inheritable});
        }

        void clear() {
            maps.remove(Thread.currentThread());
        }

        int hash(Object local) {
            return System.identityHashCode(local);
        }

        private Map<Object, boolean[]> map() {
            Map<Object, boolean[]> map = maps.get(Thread.currentThread());
            return map == null ? Map.of() : map;
        }

        @Override
        public int snapshot(int[] hashes) {
            snapshots++;
            if (tooLarge) {
                return ThreadLocals.TOO_LARGE;
            }
            int count = 0;
            for (Map.Entry<Object, boolean[]> entry : map().entrySet()) {
                if (entry.getValue()[0]) {
                    if (count == hashes.length) {
                        return ThreadLocals.OVERFLOW;
                    }
                    hashes[count++] = hash(entry.getKey());
                }
            }
            return count;
        }

        @Override
        public int leftovers(int[] open, int openCount, Object[] keys, int[] hashes, boolean[] inheritable) {
            int found = 0;
            for (Map.Entry<Object, boolean[]> entry : map().entrySet()) {
                int hash = hash(entry.getKey());
                if (entry.getValue()[0] && !ThreadLocals.contains(open, openCount, hash)) {
                    if (found < keys.length) {
                        keys[found] = entry.getKey();
                        hashes[found] = hash;
                        inheritable[found] = entry.getValue()[1];
                    }
                    found++;
                }
            }
            return found;
        }
    }

    static final class FakeResolver extends ThreadLocals.Resolver {

        @Override
        public String[] resolve(Object threadLocal, String[] packages, String[] holders, long budgetNanos) {
            return new String[] {"resolved " + threadLocal.getClass().getName(), "false", "true", null};
        }

        @Override
        public String initializationCheck() {
            return "test";
        }
    }

    private long enabledClaim() {
        long token = claim(List.of(SideEffects.THREAD_LOCALS));
        SideEffects.enable(SideEffects.MASK_THREAD_LOCALS);
        return token;
    }

    private long claim(List<String> sensors) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "shop");
        request.put("mode", "dev");
        request.put("packages", List.of("com.example"));
        request.put("sensors", sensors);
        Supplier<Object> capture = context::get;
        Function<Object, AutoCloseable> reopen = snapshot -> null;
        keep.add(capture);
        keep.add(reopen);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        assertThat(result.get("status")).isEqualTo(AgentBridge.ARMED);
        return (Long) result.get("token");
    }

    private static Object[] owner(String request) {
        return new Object[] {request, null, null, null, null, null, null, 1L, 1L};
    }

    private static long generation() {
        return (Long) ((Map<?, ?>) AgentBridge.status().get("claim")).get("generation");
    }

    private static int detail(long[] record) {
        return (int) ((record[SideEffects.R_FLAGS] >>> 32) & 0xFF);
    }

    private static int id(long[] record) {
        return (int) ((record[SideEffects.R_FLAGS] >>> 40) & 0xFFFF);
    }

    private static List<long[]> drain(long token) {
        List<long[]> records = new ArrayList<>();
        SideEffects.drain(token, record -> records.add(record.clone()));
        return records;
    }

    private static List<String> interned() {
        String[] strings = SideEffects.interned(generation(), 1);
        return strings == null ? List.of() : Arrays.asList(strings);
    }

    private static String string(long id) {
        if (id <= 0) {
            return null;
        }
        String[] strings = SideEffects.interned(generation(), (int) id);
        return strings == null || strings.length == 0 ? null : strings[0];
    }
}
