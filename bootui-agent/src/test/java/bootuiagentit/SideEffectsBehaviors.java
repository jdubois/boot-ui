package bootuiagentit;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The side-effect sensors' behaviors (PLAN-v2 §5.16, M5-5a), in a forked JVM beside the agent, claimed with the
 * {@code processes} sensor alone and a harness engine whose context is a thread-local request id. Prints one PASS or
 * FAIL line per behavior, then the bridge's status. With {@code mockito-first} or {@code bootui-first}, Mockito's inline
 * mock maker mocks {@code ProcessBuilder} before or after the claim.
 */
public final class SideEffectsBehaviors {

    static final String REQUEST = "00000000000000ef";
    static final long REQUEST_BITS = 0xefL;
    static final String SECRET = "hunter2-bootui-secret";

    static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();
    static final List<String> RESULTS = new ArrayList<>();
    static final List<long[]> RECORDS = new ArrayList<>();
    static Supplier<Object> capture;
    static Function<Object, AutoCloseable> reopen;
    static long token;
    static long generation;

    private SideEffectsBehaviors() {}

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "behaviors";
        // Loaded and used before the claim, as an application's ProcessBuilder is: the hook retransforms it.
        new ProcessBuilder("bootui-before-claim").command();
        Object mock = null;
        if ("mockito-first".equals(mode)) {
            mock = mockProcessBuilder();
        }
        token = claim();
        awaitSelfTest(SideEffects.PROCESSES);
        if ("bootui-first".equals(mode)) {
            mock = mockProcessBuilder();
        }
        if (mock != null) {
            mockito(mock);
        } else if (!"check".equals(mode)) {
            startedProcess();
            runtimeExec();
            pipeline();
            failedStart();
            unowned();
            bootUiWork();
            releaseRestores();
        }
        Map<String, Object> status = AgentBridge.status();
        System.out.println("PROCESSES=" + status.get(SideEffects.PROCESSES));
        System.out.println("SENSOR=" + sensor(SideEffects.PROCESSES));
        RESULTS.forEach(System.out::println);
        System.out.println("STATUS=" + status);
    }

    static long claim() {
        Object marker = new Object();
        capture = () -> CONTEXT.get() == null || marker == null
                ? null
                : new Object[] {CONTEXT.get(), null, null, null, "/reports", null, null, 1L, 1L};
        reopen = argument -> null;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "side-effects-behaviors");
        request.put("mode", "dev");
        request.put("packages", List.of("bootuiagentit"));
        request.put("sensors", List.of(SideEffects.PROCESSES));
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        System.out.println("CLAIM=" + result.get("status"));
        generation = (Long) result.get("generation");
        return (Long) result.get("token");
    }

    static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    static void startedProcess() throws Exception {
        RECORDS.clear();
        CONTEXT.set(REQUEST);
        ProcessBuilder builder =
                new ProcessBuilder(java(), "-Dbootui.secret=" + SECRET, "-version").redirectErrorStream(true);
        builder.environment().put("BOOTUI_SECRET_VARIABLE", SECRET);
        Process process = builder.start();
        CONTEXT.remove();
        process.getInputStream().readAllBytes();
        process.waitFor(30, TimeUnit.SECONDS);
        long[] start = await(kind(SideEffects.KIND_PROCESS_START));
        long[] exit = await(kind(SideEffects.KIND_PROCESS_EXIT));
        List<String> strings = interned();
        check(
                "a started process records its command's file name and its exit, never an argument or the environment ("
                        + describe(RECORDS) + " " + strings + ")",
                start != null
                        && exit != null
                        && "java".equals(string(start[SideEffects.R_TARGET]))
                        && start[SideEffects.R_REQUEST] == REQUEST_BITS
                        && outcome(start) == SideEffects.OUTCOME_STARTED
                        && exit[SideEffects.R_REQUEST] == REQUEST_BITS
                        && outcome(exit) == SideEffects.OUTCOME_EXITED
                        && (int) (exit[SideEffects.R_FLAGS] >>> 32) == 0
                        && exit[SideEffects.R_NANOS] > 0
                        && string((int) start[SideEffects.R_FRAMES]) != null
                        && string((int) start[SideEffects.R_FRAMES]).startsWith("bootuiagentit.SideEffectsBehaviors#")
                        && strings.stream().noneMatch(text -> text.contains(SECRET) || text.contains("-version")));
    }

    static void runtimeExec() throws Exception {
        RECORDS.clear();
        CONTEXT.set(REQUEST);
        Process process = Runtime.getRuntime().exec(new String[] {java(), "-version"});
        CONTEXT.remove();
        process.getErrorStream().readAllBytes();
        process.waitFor(30, TimeUnit.SECONDS);
        long[] start = await(kind(SideEffects.KIND_PROCESS_START));
        check(
                "Runtime.exec records through ProcessBuilder (" + describe(RECORDS) + ")",
                start != null && "java".equals(string(start[SideEffects.R_TARGET])));
    }

    static void pipeline() throws Exception {
        RECORDS.clear();
        CONTEXT.set(REQUEST);
        List<Process> processes = ProcessBuilder.startPipeline(List.of(
                new ProcessBuilder(java(), "-version"),
                new ProcessBuilder(java(), "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD)));
        CONTEXT.remove();
        for (Process process : processes) {
            process.waitFor(30, TimeUnit.SECONDS);
        }
        await(records -> records.stream()
                        .filter(record -> record[SideEffects.R_KIND] == SideEffects.KIND_PROCESS_START)
                        .count()
                >= 2);
        long starts = RECORDS.stream()
                .filter(record -> record[SideEffects.R_KIND] == SideEffects.KIND_PROCESS_START)
                .mapToLong(record -> record[SideEffects.R_COUNT])
                .sum();
        check("ProcessBuilder.startPipeline records each process (" + describe(RECORDS) + ")", starts == 2);
    }

    static void failedStart() throws Exception {
        RECORDS.clear();
        CONTEXT.set(REQUEST);
        boolean failed = false;
        try {
            new ProcessBuilder("/nonexistent-bootui/bootui-missing-command", SECRET).start();
        } catch (IOException expected) {
            failed = true;
        }
        CONTEXT.remove();
        long[] start = await(kind(SideEffects.KIND_PROCESS_START));
        check(
                "a command that cannot start records its failure (" + describe(RECORDS) + ")",
                failed
                        && start != null
                        && outcome(start) == SideEffects.OUTCOME_IO_ERROR
                        && "bootui-missing-command".equals(string(start[SideEffects.R_TARGET]))
                        && interned().stream().noneMatch(text -> text.contains(SECRET)));
    }

    static void unowned() throws Exception {
        RECORDS.clear();
        try {
            new ProcessBuilder("/nonexistent-bootui/unowned").start();
        } catch (IOException expected) {
            // Recorded all the same.
        }
        long[] start = await(kind(SideEffects.KIND_PROCESS_START));
        check(
                "unowned work names its thread (" + describe(RECORDS) + ")",
                start != null
                        && start[SideEffects.R_REQUEST] == 0L
                        && Thread.currentThread().getName().equals(string((int)
                                ((start[SideEffects.R_FLAGS] >>> 16) & 0xFFFF))));
    }

    static void bootUiWork() throws Exception {
        RECORDS.clear();
        boolean previous = AgentBridge.bootUiWork(true);
        try {
            new ProcessBuilder("/nonexistent-bootui/bootui-work").start();
        } catch (IOException expected) {
            // BootUI's own: never recorded.
        } finally {
            AgentBridge.bootUiWork(previous);
        }
        Thread.sleep(200);
        drain();
        check("BootUI's own work is never recorded (" + describe(RECORDS) + ")", RECORDS.isEmpty());
    }

    static void releaseRestores() throws Exception {
        AgentBridge.release("side-effects-behaviors", "dev");
        Object state = awaitState(SideEffects.PROCESSES, "released");
        SideEffects.beginSelfTest();
        try {
            new ProcessBuilder("bootui-after-release\u0000").start();
        } catch (IOException expected) {
            // Refused before spawning, as the self-test's command is.
        }
        Map<String, Object> hits = SideEffects.endSelfTest();
        check(
                "release restores ProcessBuilder (" + state + ", " + hits + ")",
                "released".equals(state) && Long.valueOf(0L).equals(hits.get("ProcessBuilder.start")));
    }

    static Object mockProcessBuilder() {
        return org.mockito.Mockito.mock(ProcessBuilder.class);
    }

    static void mockito(Object mock) throws Exception {
        ProcessBuilder builder = (ProcessBuilder) mock;
        Process stub = org.mockito.Mockito.mock(Process.class);
        org.mockito.Mockito.when(builder.start()).thenReturn(stub);
        RECORDS.clear();
        CONTEXT.set(REQUEST);
        Process stubbed = builder.start();
        Thread.sleep(200);
        drain();
        int afterStub = RECORDS.size();
        boolean failed = false;
        try {
            new ProcessBuilder("/nonexistent-bootui/real-after-mock").start();
        } catch (IOException expected) {
            failed = true;
        }
        CONTEXT.remove();
        long[] start = await(kind(SideEffects.KIND_PROCESS_START));
        System.out.println("MOCKITO="
                + (stubbed == stub && afterStub == 0 && failed ? "ok" : "unexpected " + stubbed + " " + afterStub));
        check(
                "a real ProcessBuilder beside a Mockito mock still records (" + describe(RECORDS) + ")",
                start != null && "real-after-mock".equals(string(start[SideEffects.R_TARGET])));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    static Predicate<List<long[]>> kind(int kind) {
        return records -> records.stream().anyMatch(record -> record[SideEffects.R_KIND] == kind);
    }

    /** Drains until {@code done} holds, for up to 15 seconds: the first record of the predicate's kind, if any. */
    static long[] await(Predicate<List<long[]>> done) throws Exception {
        for (int i = 0; i < 300; i++) {
            drain();
            if (done.test(RECORDS)) {
                break;
            }
            Thread.sleep(50);
        }
        return RECORDS.isEmpty() ? null : last(done);
    }

    private static long[] last(Predicate<List<long[]>> done) {
        for (long[] record : RECORDS) {
            if (done.test(List.of(record))) {
                return record;
            }
        }
        return null;
    }

    static void drain() {
        SideEffects.drain(token, record -> RECORDS.add(record.clone()));
    }

    static List<String> interned() {
        String[] strings = SideEffects.interned(generation, 1);
        return strings == null ? List.of() : Arrays.asList(strings);
    }

    static String string(long id) {
        if (id <= 0) {
            return null;
        }
        String[] strings = SideEffects.interned(generation, (int) id);
        return strings == null || strings.length == 0 ? null : strings[0];
    }

    static int outcome(long[] record) {
        return (int) (record[SideEffects.R_FLAGS] & 0xFF);
    }

    static String describe(List<long[]> records) {
        List<String> described = new ArrayList<>();
        for (long[] record : records) {
            described.add("kind=" + record[SideEffects.R_KIND] + " target=" + string(record[SideEffects.R_TARGET])
                    + " outcome=" + outcome(record) + " request=" + Long.toHexString(record[SideEffects.R_REQUEST])
                    + " count=" + record[SideEffects.R_COUNT]);
        }
        return described.toString();
    }

    static void awaitSelfTest(String id) throws Exception {
        Map<String, Object> sensor = Map.of();
        for (int i = 0; i < 400; i++) {
            sensor = sensor(id);
            if (Boolean.TRUE.equals(sensor.get("selfTestPassed")) || sensor.get("selfTestError") != null) {
                break;
            }
            Thread.sleep(25);
        }
        System.out.println("SELF_TEST_" + id + "=" + sensor.get("selfTestPassed") + " " + sensor.get("selfTestError")
                + " " + sensor.get("hooks"));
    }

    static Object awaitState(String id, String expected) throws Exception {
        Object state = null;
        for (int i = 0; i < 400; i++) {
            Map<String, Object> sensor = sensor(id);
            state = sensor.get("state");
            if (expected.equals(state) && Boolean.TRUE.equals(sensor.get("idle"))) {
                return state;
            }
            Thread.sleep(25);
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sensor(String id) {
        Map<String, Object> agent = (Map<String, Object>) AgentBridge.status().get("agent");
        for (Object item : (List<Object>) agent.get("sensors")) {
            Map<String, Object> sensor = (Map<String, Object>) item;
            if (id.equals(sensor.get("id"))) {
                return sensor;
            }
        }
        return Map.of();
    }

    static void check(String name, boolean ok) {
        RESULTS.add((ok ? "  PASS " : "  FAIL ") + name);
    }
}
