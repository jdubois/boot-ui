package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The side-effect sensors' bridge side (PLAN-v2 §5.16, M5-5a), driven as the processes sensor's delegating advice on
 * {@code ProcessBuilder.start(Redirect[])} would drive it: {@code processStarting} at its entry and
 * {@code processStarted} with the token at its exit.
 */
class SideEffectsTests {

    private static final String REQUEST = "00000000000000ab";
    private static final long REQUEST_BITS = 0xabL;

    private final List<Object> keep = new ArrayList<>();
    private final AtomicReference<Object[]> context = new AtomicReference<>();
    private final AtomicInteger captures = new AtomicInteger();
    private final AtomicReference<RuntimeException> captureFailure = new AtomicReference<>();

    @BeforeEach
    void install() {
        AgentBridge.reset();
        AgentBridge.install(request -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "ok");
            return answer;
        });
    }

    @AfterEach
    void reset() {
        SideEffects.exitWorkerRunning(false);
        SideEffects.exitQueue().clear();
        AgentBridge.reset();
    }

    @Test
    void nothingRecordsUntilTheAgentEnabledTheSensor() {
        long token = claim(List.of(SideEffects.PROCESSES));
        context.set(owner(REQUEST));

        assertThat(SideEffects.processStarting()).isZero();
        SideEffects.enable(SideEffects.MASK_PROCESSES);
        long started = SideEffects.processStarting();
        assertThat(started).isNotZero();
        SideEffects.processStarted(started, List.of("ls"), null, new IOException("no"));

        assertThat(drain(token)).hasSize(1);
    }

    @Test
    void aClaimWithoutTheSensorRecordsNothingEvenWhenEnabled() {
        long token = claim(List.of("executors"));
        SideEffects.enable(SideEffects.MASK_PROCESSES);
        context.set(owner(REQUEST));

        assertThat(SideEffects.processStarting()).isZero();
        assertThat(drain(token)).isEmpty();
    }

    @Test
    void aFailedStartRecordsTheCommandsFileNameOnlyNeverAnArgument() {
        long token = enabledClaim();
        context.set(owner(REQUEST));
        ProcessBuilder builder =
                new ProcessBuilder("/opt/tools/secret-tool", "--password", "hunter2", "SECRET_TOKEN=abc123");
        builder.environment().put("API_KEY", "s3cr3t-value");

        long started = SideEffects.processStarting();
        SideEffects.processStarted(started, builder.command(), null, new IOException("error=2"));

        List<long[]> records = drain(token);
        assertThat(records).hasSize(1);
        long[] record = records.get(0);
        assertThat(record[SideEffects.R_SENSOR]).isEqualTo(SideEffects.SENSOR_PROCESSES);
        assertThat(record[SideEffects.R_KIND]).isEqualTo(SideEffects.KIND_PROCESS_START);
        assertThat(record[SideEffects.R_REQUEST]).isEqualTo(REQUEST_BITS);
        assertThat(record[SideEffects.R_COUNT]).isEqualTo(1L);
        assertThat(outcome(record)).isEqualTo(SideEffects.OUTCOME_IO_ERROR);
        assertThat(string(record[SideEffects.R_TARGET])).isEqualTo("secret-tool");
        assertThat(String.join("|", interned()))
                .doesNotContain("hunter2")
                .doesNotContain("--password")
                .doesNotContain("abc123")
                .doesNotContain("s3cr3t-value")
                .doesNotContain("API_KEY")
                .doesNotContain("/opt/tools");
    }

    @Test
    void aStartedProcessRecordsItsStartAndItsExitStatusAndDuration() throws Exception {
        long token = enabledClaim();
        SideEffects.exitWorkerRunning(true);
        context.set(owner(REQUEST));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-version").redirectErrorStream(true);

        long started = SideEffects.processStarting();
        Process process = null;
        Throwable thrown = null;
        try {
            process = builder.start();
        } catch (IOException ex) {
            thrown = ex;
        } finally {
            SideEffects.processStarted(started, builder.command(), process, thrown);
        }
        assertThat(process).isNotNull();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        Runnable exit = SideEffects.exitQueue().poll(30, TimeUnit.SECONDS);
        assertThat(exit)
                .as("the exit completes on the side-effect sensors' executor")
                .isNotNull();
        exit.run();

        List<long[]> records = drain(token);
        assertThat(records).hasSize(2);
        assertThat(records.get(0)[SideEffects.R_KIND]).isEqualTo(SideEffects.KIND_PROCESS_START);
        assertThat(outcome(records.get(0))).isEqualTo(SideEffects.OUTCOME_STARTED);
        long[] exited = records.get(1);
        assertThat(exited[SideEffects.R_KIND]).isEqualTo(SideEffects.KIND_PROCESS_EXIT);
        assertThat(outcome(exited)).isEqualTo(SideEffects.OUTCOME_EXITED);
        assertThat((int) (exited[SideEffects.R_FLAGS] >>> 32)).isZero();
        assertThat(exited[SideEffects.R_REQUEST]).isEqualTo(REQUEST_BITS);
        assertThat(exited[SideEffects.R_TARGET]).isEqualTo(records.get(0)[SideEffects.R_TARGET]);
        assertThat(exited[SideEffects.R_NANOS]).isPositive();
        assertThat(string(exited[SideEffects.R_TARGET])).isEqualTo("java");
        assertThat(String.join("|", interned())).doesNotContain("-version");
        assertThat(SideEffects.status(SideEffects.PROCESSES))
                .containsEntry("exitsWatched", 1L)
                .containsEntry("exitsRecorded", 1L)
                .containsEntry("exitsPending", 0);
    }

    @Test
    void withoutTheExitWorkerAStartedProcessIsNotWatched() throws Exception {
        long token = enabledClaim();
        context.set(owner(REQUEST));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-version").redirectErrorStream(true);

        long started = SideEffects.processStarting();
        Process process = builder.start();
        SideEffects.processStarted(started, builder.command(), process, null);
        process.getInputStream().readAllBytes();
        process.waitFor(30, TimeUnit.SECONDS);

        assertThat(drain(token)).hasSize(1);
        assertThat(SideEffects.status(SideEffects.PROCESSES)).containsEntry("exitsUnwatched", 1L);
    }

    @Test
    void insideAnAdaptersScopeTheSlotNamesTheOwnerWithoutAnotherCapture() {
        long token = claim(List.of(CodePaths.SENSOR, SideEffects.PROCESSES));
        SideEffects.enable(SideEffects.MASK_PROCESSES);
        context.set(owner(REQUEST));

        CodePaths.begin();
        assertThat(captures).hasValue(1);
        context.set(null);
        for (int i = 0; i < 3; i++) {
            long started = SideEffects.processStarting();
            SideEffects.processStarted(started, List.of("git", "status"), null, new IOException("no"));
        }
        CodePaths.end();

        List<long[]> records = drain(token);
        assertThat(records).as("a rare hook publishes each start at once").hasSize(3);
        assertThat(records)
                .allSatisfy(record -> assertThat(record[SideEffects.R_REQUEST]).isEqualTo(REQUEST_BITS));
        assertThat(captures).as("the slot's owner, captured once by the scope").hasValue(1);
    }

    @Test
    void aHandoffNamesItsSubmittersRequestUntilItIsDone() {
        long token = enabledClaim();
        context.set(null);

        SideEffects.handoff(owner(REQUEST), generation());
        long started = SideEffects.processStarting();
        SideEffects.processStarted(started, List.of("curl"), null, new IOException("no"));
        SideEffects.handoffDone();
        started = SideEffects.processStarting();
        SideEffects.processStarted(started, List.of("curl"), null, new IOException("no"));

        List<long[]> records = drain(token);
        assertThat(records).hasSize(2);
        assertThat(records.get(0)[SideEffects.R_REQUEST]).isEqualTo(REQUEST_BITS);
        assertThat(records.get(1)[SideEffects.R_REQUEST])
                .as("done: unowned again")
                .isZero();
        assertThat(captures)
                .as("the first named by the slot, the second captured")
                .hasValue(1);
    }

    @Test
    void anotherRequestsTaskRunInsideAScopeIsThatRequestsAndTheScopeIsBackAfterIt() {
        long token = claim(List.of(CodePaths.SENSOR, SideEffects.PROCESSES));
        SideEffects.enable(SideEffects.MASK_PROCESSES);
        context.set(owner(REQUEST));
        CodePaths.begin();
        context.set(null);

        SideEffects.handoff(owner("00000000000000cd"), generation());
        long started = SideEffects.processStarting();
        SideEffects.processStarted(started, List.of("inner"), null, new IOException("no"));
        SideEffects.handoffDone();
        started = SideEffects.processStarting();
        SideEffects.processStarted(started, List.of("outer"), null, new IOException("no"));
        CodePaths.end();

        List<long[]> records = drain(token);
        assertThat(records).hasSize(2);
        assertThat(records.get(0)[SideEffects.R_REQUEST]).isEqualTo(0xcdL);
        assertThat(records.get(1)[SideEffects.R_REQUEST]).isEqualTo(REQUEST_BITS);
        assertThat(SideEffects.status(SideEffects.PROCESSES)).containsEntry("slotMismatches", 0L);
    }

    @Test
    void aHotHooksTableAggregatesPerKeyAndFlushesWhenItsSlotIsPopped() {
        long token = enabledClaim();
        context.set(null);
        SideEffects.handoff(owner(REQUEST), generation());
        CodePaths.Frame frame = CodePaths.FRAME.get();
        SideEffects.Owner owner = new SideEffects.Owner();
        owner.generation = generation();
        owner.request = REQUEST_BITS;
        owner.slot = true;
        for (int i = 0; i < 3; i++) {
            SideEffects.record(frame, owner, SideEffects.SENSOR_PROCESSES, 7, 1, 1, 0, 0L, 0L, 10L + i);
        }
        assertThat(drain(token)).as("held in the thread's table").isEmpty();

        SideEffects.handoffDone();

        List<long[]> records = drain(token);
        assertThat(records).hasSize(1);
        assertThat(records.get(0)[SideEffects.R_COUNT]).isEqualTo(3L);
        assertThat(records.get(0)[SideEffects.R_NANOS]).isEqualTo(33L);
        assertThat(records.get(0)[SideEffects.R_MAX_NANOS]).isEqualTo(12L);
        assertThat(records.get(0)[SideEffects.R_REQUEST]).isEqualTo(REQUEST_BITS);
    }

    @Test
    void anUnownedRecordNamesItsThread() {
        long token = enabledClaim();
        context.set(null);

        long started = SideEffects.processStarting();
        SideEffects.processStarted(started, List.of("sh"), null, new IOException("no"));

        long[] record = drain(token).get(0);
        assertThat(record[SideEffects.R_REQUEST]).isZero();
        assertThat(record[SideEffects.R_EXECUTION]).isZero();
        int thread = (int) ((record[SideEffects.R_FLAGS] >>> 16) & 0xFFFF);
        assertThat(string(thread)).isEqualTo(Thread.currentThread().getName());
        assertThat((record[SideEffects.R_FLAGS] >>> 8) & 0xF).isEqualTo(SideEffects.THREAD_PLATFORM);
    }

    @Test
    void onlyTheOutermostHookOnAThreadRecords() {
        long token = enabledClaim();
        context.set(owner(REQUEST));

        long outer = SideEffects.processStarting();
        assertThat(SideEffects.processStarting()).as("nested").isZero();
        SideEffects.processStarted(outer, List.of("a"), null, new IOException("no"));
        long again = SideEffects.processStarting();
        assertThat(again).as("balanced after the outer exit").isNotZero();
        SideEffects.processStarted(again, List.of("a"), null, new IOException("no"));

        assertThat(drain(token)).hasSize(2);
    }

    @Test
    void bootUiWorkTheAgentsTransformationsAndBootUiThreadsAreSkipped() throws Exception {
        long token = enabledClaim();
        context.set(owner(REQUEST));

        boolean previous = AgentBridge.bootUiWork(true);
        assertThat(SideEffects.processStarting()).isZero();
        AgentBridge.bootUiWork(previous);
        SideEffects.agentWork(true);
        SideEffects.agentWork(true);
        SideEffects.agentWork(false);
        assertThat(SideEffects.processStarting())
                .as("still inside the outer transformation")
                .isZero();
        SideEffects.agentWork(false);
        SideEffects.agentWork(false);
        long started = SideEffects.processStarting();
        assertThat(started).isNotZero();
        SideEffects.processStarted(started, List.of("ok"), null, new IOException("no"));

        long[] named = new long[1];
        Thread bootUi = new Thread(() -> named[0] = SideEffects.processStarting(), "bootui-worker");
        bootUi.start();
        bootUi.join();
        long[] marked = new long[1];
        Thread agent = new Thread(() -> {
            SideEffects.markBootUiThread();
            marked[0] = SideEffects.processStarting();
        });
        agent.start();
        agent.join();

        assertThat(named[0]).isZero();
        assertThat(marked[0]).isZero();
        assertThat(drain(token)).hasSize(1);
    }

    @Test
    void theSelfTestThreadIsCountedPerHookAndRecordsNothing() {
        long token = claim(List.of(SideEffects.PROCESSES));

        SideEffects.beginSelfTest();
        assertThat(SideEffects.processStarting()).isZero();
        Map<String, Object> hits = SideEffects.endSelfTest();

        assertThat(hits).containsEntry("ProcessBuilder.start", 1L);
        assertThat(drain(token)).isEmpty();
    }

    @Test
    void aDisarmedClaimStopsRecording() {
        long token = enabledClaim();
        context.set(owner(REQUEST));
        AgentBridge.disarm(token);

        assertThat(SideEffects.processStarting()).isZero();
        assertThat(SideEffects.status(SideEffects.PROCESSES)).containsEntry("active", false);
    }

    @Test
    void aFrameSummaryNamesTheFirstFrameOutsideTheJdkAndTheFirstApplicationFrame() {
        long token = claim(List.of(SideEffects.PROCESSES), List.of("org.junit.jupiter"));
        SideEffects.enable(SideEffects.MASK_PROCESSES);
        context.set(owner(REQUEST));

        long started = SideEffects.processStarting();
        SideEffects.processStarted(started, List.of("x"), null, new IOException("no"));

        long frames = drain(token).get(0)[SideEffects.R_FRAMES];
        String outside = string((int) (frames >>> 32));
        String application = string((int) frames);
        assertThat(outside).contains("#").doesNotStartWith("java.").doesNotStartWith("io.github.jdubois.bootui.agent.");
        assertThat(application).startsWith("org.junit.jupiter.").contains("#");
    }

    @Test
    void internalErrorsSwitchTheSensorsOffAfterTheBudget() {
        long token = enabledClaim();
        captureFailure.set(new IllegalStateException("capture failed"));

        for (int i = 0; i < SideEffects.MAX_ERRORS; i++) {
            long started = SideEffects.processStarting();
            SideEffects.processStarted(started, List.of("x"), null, new IOException("no"));
        }

        assertThat(SideEffects.processStarting()).isZero();
        Map<String, Object> status = SideEffects.status(SideEffects.PROCESSES);
        assertThat(status).containsEntry("off", true);
        assertThat((String) status.get("disabledReason")).contains("internal errors");
        assertThat(drain(token)).isEmpty();
    }

    @Test
    void theCommandNameIsTheFileNameWithControlCharactersReplacedAndBounded() {
        assertThat(SideEffects.commandName(new ProcessBuilder("C:\\tools\\deploy.exe", "x").command(), false))
                .isEqualTo("deploy.exe");
        assertThat(SideEffects.commandName(new ProcessBuilder("/usr/bin/").command(), false))
                .isEqualTo("(empty)");
        assertThat(SideEffects.commandName(new ArrayList<String>(), false)).isEqualTo("(empty)");
        assertThat(SideEffects.commandName(new ProcessBuilder("bad\u0000name").command(), false))
                .isEqualTo("bad?name");
        assertThat(SideEffects.commandName(new ProcessBuilder("/bin/sh -c 'cat /etc/secret-file'").command(), false))
                .as("a command and its arguments in one element")
                .isEqualTo("sh");
        assertThat(SideEffects.commandName(new ProcessBuilder("\tleading").command(), false))
                .isEqualTo("(empty)");
        assertThat(SideEffects.commandName(List.of("GH_TOKEN=ghp_secretvalue gh api"), false))
                .as("an environment assignment keeps the variable's name only")
                .isEqualTo("GH_TOKEN");
        assertThat(SideEffects.commandName(List.of("\"C:\\Program Files\\tool.exe\" --key=abc"), false))
                .as("a quoted executable keeps its quoted text only")
                .isEqualTo("tool.exe");
        assertThat(SideEffects.commandName(List.of("AWS_SECRET_ACCESS_KEY=wJalr/K7MDENG/bPxRfiCYKEY aws s3 ls"), false))
                .as("a value holding a path separator never leaves its tail")
                .isEqualTo("AWS_SECRET_ACCESS_KEY");
        assertThat(SideEffects.commandName(List.of("tool's"), false))
                .as("only letters, digits, and . _ + - are kept")
                .isEqualTo("tool?s");
        char[] longName = new char[500];
        Arrays.fill(longName, 'a');
        assertThat(SideEffects.commandName(List.of(new String(longName)), false))
                .hasSize(SideEffects.MAX_TARGET);
    }

    @Test
    void aStartedProcessKeepsItsWholeExecutablesFileNameSpacesInItsPathIncluded() {
        assertThat(SideEffects.commandName(List.of("C:\\Program Files\\Java\\jdk-21\\bin\\java.exe", "-version"), true))
                .isEqualTo("java.exe");
        assertThat(SideEffects.commandName(List.of("/opt/my tools/bin/run report"), true))
                .as("a file name with a space, which started")
                .isEqualTo("run?report");
        assertThat(SideEffects.commandName(List.of("C:\\Program Files\\Java\\jdk-21\\bin\\java.exe"), false))
                .as("the same path, when the start failed, is cut at its first whitespace")
                .isEqualTo("Program");
        // Windows passes one quoted element verbatim to CreateProcess, which starts it: never past the closing quote.
        assertThat(SideEffects.commandName(List.of("\"C:\\Tools\\app.exe\" --token \"abc\""), true))
                .isEqualTo("app.exe");
        assertThat(SideEffects.commandName(
                        List.of("\"C:\\Program Files\\Git\\bin\\git.exe\" commit -m \"C:\\secret\\msg\""), true))
                .isEqualTo("git.exe");
        assertThat(SideEffects.commandName(List.of("\"C:\\Tools\\app.exe --token abc"), true))
                .as("an unclosed quote is cut at its first whitespace")
                .isEqualTo("app.exe");
        // Windows wraps an element with an inner unpaired quote and a space in quotes, which CreateProcess then starts
        // with the text after the inner quote as arguments: never past it.
        assertThat(SideEffects.commandName(List.of("C:\\Tools\\app.exe\" --token \"abc\""), true))
                .isEqualTo("app.exe");
        assertThat(SideEffects.commandName(List.of("cmd\" /c \"type secret"), true))
                .isEqualTo("cmd");
        assertThat(SideEffects.commandName(List.of("C:\\x\" --token y"), true)).isEqualTo("x");
        assertThat(SideEffects.commandName(List.of("C:\\x\" --token y"), false)).isEqualTo("x");
    }

    @Test
    void anExecutionNoRequestOwnsIsRecordedWithItsKind() {
        long token = enabledClaim();
        context.set(new Object[] {null, "00000000000000ef", null, null, null, null, null, 1L, 1L});

        long started = SideEffects.processStarting();
        SideEffects.processStarted(started, List.of("report"), null, new IOException("no"));

        long[] record = drain(token).get(0);
        assertThat(record[SideEffects.R_REQUEST]).isZero();
        assertThat(record[SideEffects.R_EXECUTION]).isEqualTo(0xefL);
        assertThat((record[SideEffects.R_FLAGS] >>> 12) & 0xF).isEqualTo(SideEffects.EXECUTION_OWN);
    }

    @Test
    void aSlotOfAnEarlierGenerationIsIgnoredAndTheOwnerCaptured() {
        enabledClaim();
        context.set(null);
        SideEffects.handoff(owner(REQUEST), generation());
        long token = enabledClaim();
        context.set(owner("00000000000000cd"));

        long started = SideEffects.processStarting();
        SideEffects.processStarted(started, List.of("git"), null, new IOException("no"));

        List<long[]> records = drain(token);
        assertThat(records)
                .singleElement()
                .satisfies(record -> assertThat(record[SideEffects.R_REQUEST])
                        .as("captured for the new run, not the previous run's slot")
                        .isEqualTo(0xcdL));
        SideEffects.handoffDone();
    }

    @Test
    void aFullRingDropsAndCountsPerSensor() {
        enabledClaim();
        context.set(null);
        for (int i = 0; i < SideEffects.DEFAULT_CAPACITY + 5; i++) {
            long started = SideEffects.processStarting();
            SideEffects.processStarted(started, List.of("x"), null, new IOException("no"));
        }

        assertThat(SideEffects.status(SideEffects.PROCESSES)).containsEntry("dropped", 5L);
    }

    private long enabledClaim() {
        long token = claim(List.of(SideEffects.PROCESSES));
        SideEffects.enable(SideEffects.MASK_PROCESSES);
        return token;
    }

    private long claim(List<String> sensors) {
        return claim(sensors, List.of("com.example"));
    }

    private long claim(List<String> sensors, List<String> packages) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "shop");
        request.put("mode", "dev");
        request.put("packages", packages);
        request.put("sensors", sensors);
        Supplier<Object> capture = () -> {
            captures.incrementAndGet();
            RuntimeException failure = captureFailure.get();
            if (failure != null) {
                throw failure;
            }
            return context.get();
        };
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

    private static int outcome(long[] record) {
        return (int) (record[SideEffects.R_FLAGS] & 0xFF);
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

    @Test
    void anotherSensorsReinstallKeepsWhatThreadActivityWaitsToCheck() {
        ThreadActivity.reset();
        Object executor = new Object();
        SideEffects.enable(SideEffects.MASK_THREADS | SideEffects.MASK_FILES);
        try {
            assertThat(ThreadActivity.TRACKER.track(
                            executor,
                            false,
                            1L,
                            0x42L,
                            0L,
                            0,
                            1,
                            0,
                            3,
                            5L,
                            9L,
                            0,
                            System.currentTimeMillis(),
                            new ArrayList<>()))
                    .isTrue();

            SideEffects.disable(SideEffects.MASK_FILES, null);
            assertThat(ThreadActivity.TRACKER.size())
                    .as("a files switch never drops thread-activity's pending checks")
                    .isEqualTo(1);

            SideEffects.disable(SideEffects.MASK_THREADS, null);
            assertThat(ThreadActivity.TRACKER.size()).isZero();
            assertThat(ThreadActivity.TRACKER.dropped.sum()).isEqualTo(1L);
        } finally {
            SideEffects.disable(-1, null);
            ThreadActivity.reset();
        }
    }
}
