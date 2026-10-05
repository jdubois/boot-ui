package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.instrument.Instrumentation;
import java.nio.channels.FileChannel;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * The side-effect sensors (PLAN-v2 §5.16, M5-5): one transformer for every JDK side-effect hook, installed with the
 * hooks of the sensors the claim asks for, each hook delegating to the bridge ({@link SideEffectsAdvice}), and one mask
 * in the bridge saying which sensors record ({@link SideEffects#enable}). M5-5a ships {@value SideEffects#PROCESSES}
 * ({@code ProcessBuilder.start(Redirect[])}); M5-5d ships {@value SideEffects#FILES} (the private {@code open} methods
 * of {@code FileInputStream}, {@code FileOutputStream}, and {@code RandomAccessFile}, the {@code Files} methods that
 * open, delete, move, and copy, and {@code FileChannel.open}) and the opt-in {@value SideEffects#ENVIRONMENT}
 * ({@code System.getenv} and {@code System.getProperty}). A self-test per hook runs it on the sensor's worker thread,
 * which the bridge counts and never records, touching nothing that exists: the processes hook starts a command holding a
 * NUL character, which {@code ProcessBuilder} refuses before spawning anything; the files hooks open, delete, move, and
 * copy paths under a directory that does not exist; the environment hooks read a variable and a property no one sets.
 * A sensor whose hook fails its self-test is disabled for the JVM's life, the transformer removed and reinstalled with
 * the other sensors' hooks; the bridge stops it for the claim at once. A claim asking for another set of side-effect
 * sensors reinstalls the transformer with that set's hooks; a claim asking for none, or a release, removes it.
 */
final class SideEffectsSensor {

    static final String PROCESS_BUILDER = "java.lang.ProcessBuilder";
    static final String FILE_INPUT_STREAM = "java.io.FileInputStream";
    static final String FILE_OUTPUT_STREAM = "java.io.FileOutputStream";
    static final String RANDOM_ACCESS_FILE = "java.io.RandomAccessFile";
    static final String FILES = "java.nio.file.Files";
    static final String FILE_CHANNEL = "java.nio.channels.FileChannel";
    static final String SYSTEM = "java.lang.System";

    /** Every hook: its id, the type it transforms, its kind, and its sensor. */
    static final String[][] HOOKS = {
        {"ProcessBuilder.start", PROCESS_BUILDER, "record", SideEffects.PROCESSES},
        {"FileInputStream.open", FILE_INPUT_STREAM, "record", SideEffects.FILES},
        {"FileOutputStream.open", FILE_OUTPUT_STREAM, "record", SideEffects.FILES},
        {"RandomAccessFile.open", RANDOM_ACCESS_FILE, "record", SideEffects.FILES},
        {"Files.newByteChannel", FILES, "record", SideEffects.FILES},
        {"Files.newInputStream", FILES, "record", SideEffects.FILES},
        {"Files.newOutputStream", FILES, "record", SideEffects.FILES},
        {"Files.delete", FILES, "record", SideEffects.FILES},
        {"Files.deleteIfExists", FILES, "record", SideEffects.FILES},
        {"Files.move", FILES, "record", SideEffects.FILES},
        {"Files.copy", FILES, "record", SideEffects.FILES},
        {"FileChannel.open", FILE_CHANNEL, "record", SideEffects.FILES},
        {"System.getenv", SYSTEM, "record", SideEffects.ENVIRONMENT},
        {"System.getProperty", SYSTEM, "record", SideEffects.ENVIRONMENT}
    };

    /** The side-effect sensors this agent installs, in status order. */
    static final String[] SENSORS = {SideEffects.PROCESSES, SideEffects.FILES, SideEffects.ENVIRONMENT};

    /** The variable and property the environment self-test reads, which no one sets. */
    static final String SELF_TEST_NAME = "BOOTUI_AGENT_SELF_TEST_UNSET";

    static final String SELF_TEST_PROPERTY = "bootui.agent.self-test.unset";

    /** The command the processes self-test starts: {@code ProcessBuilder} refuses a NUL before spawning. */
    static final String SELF_TEST_COMMAND = "bootui-agent-self-test\u0000";

    private static final int INSTALL = 1;
    private static final int RELEASE = 2;

    private final Instrumentation instrumentation;
    private final boolean privileged;
    /** Hooks left out of the transformer: only ever non-empty in BootUI's own mutation tests. */
    private final Set<String> omitted;

    private final TransformStats stats = new TransformStats();
    private volatile ResettableClassFileTransformer transformer;
    /** The sensors the installed transformer carries the hooks of. */
    private volatile int installedMask;
    /** The sensors the current claim asks for. */
    private volatile int wantedMask;
    /** Every sensor a claim ever asked for: reported, with its state, until the JVM ends. */
    private volatile int reportedMask;
    /** The sensors whose hooks failed their self-test: never installed again in this JVM. */
    private volatile int failedMask;
    /** Why each sensor of {@link #failedMask} failed, by sensor id. */
    private final Map<String, String> failures = new java.util.concurrent.ConcurrentHashMap<String, String>();
    /** The self-test results of the hooks of {@link #failedMask}'s sensors, by hook id. */
    private final Map<String, String> failedHooks = new java.util.concurrent.ConcurrentHashMap<String, String>();

    private volatile String state = "off";
    private volatile long installMillis = -1;
    private volatile long selfTestMillis = -1;
    private volatile boolean selfTestPassed;
    private volatile String selfTestError;
    private volatile Map<String, String> selfTest = new LinkedHashMap<String, String>();
    private volatile Map<String, String> selfTestSteps = new LinkedHashMap<String, String>();
    private volatile boolean stuck;
    private Thread worker;
    private int pending;
    private boolean exitWorkerStarted;

    SideEffectsSensor(Instrumentation instrumentation, boolean privileged, Set<String> omitted) {
        this.instrumentation = instrumentation;
        this.privileged = privileged;
        this.omitted = omitted;
    }

    /** A claim asking for the side-effect sensors of {@code mask}: installs their hooks and self-tests them. */
    synchronized void claimed(int asked) {
        reportedMask |= asked;
        int mask = asked & ~failedMask;
        wantedMask = mask;
        if (stuck) {
            return;
        }
        // Only while no job runs: a release the worker is running would remove the hooks after this enabled them.
        if (transformer != null && installedMask == mask && selfTestPassed && worker == null) {
            SideEffects.enable(mask);
            return;
        }
        schedule(INSTALL);
    }

    /** Removes the transformer and restores every transformed class, off the caller's thread. */
    synchronized void release() {
        wantedMask = 0;
        schedule(RELEASE);
    }

    private void schedule(int job) {
        pending = job;
        if (worker == null) {
            worker = AgentThreads.newThread("bootui-agent-side-effects", new Worker(), privileged);
            worker.start();
        }
    }

    private synchronized int nextJob() {
        int job = pending;
        pending = 0;
        if (job == 0) {
            worker = null;
        }
        return job;
    }

    /** Whether the worker has no job left. */
    synchronized boolean idle() {
        return worker == null && pending == 0;
    }

    /** Starts the one thread process exits complete on, once per JVM, through the agent's own thread factory. */
    private synchronized void startExitWorker() {
        if (exitWorkerStarted) {
            return;
        }
        exitWorkerStarted = true;
        Runnable exits = SideEffects.exitWorker();
        if (exits != null) {
            try {
                AgentThreads.newThread("bootui-agent-process-exits", exits, privileged)
                        .start();
            } catch (Throwable ex) {
                // The sensor still records starts; exits are counted as not watched.
                stats.failure("process exits thread: " + ex);
                AgentBridge.message("the BootUI agent could not start its process exits thread: " + ex);
            }
        }
    }

    final class Worker implements Runnable {

        @Override
        public void run() {
            int job;
            while ((job = nextJob()) != 0) {
                int mask = wantedMask;
                try {
                    if ((job & RELEASE) != 0 || (job & INSTALL) != 0 && transformer != null && installedMask != mask) {
                        SideEffects.disable(installedMask, null);
                        reset();
                    }
                    if ((job & INSTALL) != 0 && !stuck && mask != 0) {
                        if (transformer == null) {
                            install(mask);
                        }
                        selfTest(mask);
                    }
                } catch (Throwable ex) {
                    selfTestPassed = false;
                    selfTestError = "side-effect sensors error: " + ex;
                    SideEffects.disable(mask, selfTestError);
                    state = "failed";
                    stats.failure("side effects: " + ex);
                    AgentBridge.message("the BootUI agent could not install its side-effect sensors: " + ex);
                }
            }
        }
    }

    void install(int mask) {
        long started = System.nanoTime();
        state = "installing";
        selfTestMillis = -1;
        SideEffects.warm();
        startExitWorker();
        InstallAction action = new InstallAction(mask);
        try {
            transformer = privileged ? (ResettableClassFileTransformer) AgentThreads.privileged(action) : action.run();
            installedMask = mask;
        } finally {
            long elapsed = System.nanoTime() - started;
            stats.retransformedFor(elapsed);
            installMillis = elapsed / 1_000_000L;
        }
        state = "testing";
    }

    void reset() {
        ResettableClassFileTransformer installed;
        synchronized (this) {
            installed = transformer;
            transformer = null;
            installedMask = 0;
            selfTestPassed = false;
        }
        if (installed == null) {
            state = stuck ? "release-failed" : "released";
            return;
        }
        long started = System.nanoTime();
        boolean restored;
        try {
            restored = installed.reset(
                    instrumentation,
                    AgentBuilder.RedefinitionStrategy.RETRANSFORMATION,
                    AgentBuilder.RedefinitionStrategy.BatchAllocator.ForFixedSize.ofSize(64),
                    new AgentBuilder.RedefinitionStrategy.Listener.Compound(
                            AgentBuilder.RedefinitionStrategy.Listener.BatchReallocator.splitting(),
                            stats.redefinitionFailures()));
        } finally {
            stats.retransformedFor(System.nanoTime() - started);
        }
        if (!restored) {
            // The hooks stay in the JDK's classes: the bridge keeps their sensors off for good.
            stuck = true;
            SideEffects.disable(-1, "the side-effect sensors' transformer could not be removed");
        }
        state = restored ? "released" : "release-failed";
    }

    final class InstallAction implements PrivilegedAction<ResettableClassFileTransformer> {

        private final int mask;

        InstallAction(int mask) {
            this.mask = mask;
        }

        @Override
        public ResettableClassFileTransformer run() {
            return builder(mask).installOn(instrumentation);
        }
    }

    private AgentBuilder builder(int mask) {
        Map<String, ExecutorSensor.Visit> visits = new LinkedHashMap<String, ExecutorSensor.Visit>();
        if ((mask & SideEffects.MASK_PROCESSES) != 0) {
            visit(visits, PROCESS_BUILDER)
                    .and(
                            "ProcessBuilder.start",
                            Advice.to(SideEffectsAdvice.ProcessStart.class)
                                    .on(ElementMatchers.named("start")
                                            .and(ElementMatchers.isPrivate())
                                            .and(ElementMatchers.takesArguments(1))
                                            .and(ElementMatchers.takesArgument(0, ProcessBuilder.Redirect[].class))));
        }
        if ((mask & SideEffects.MASK_FILES) != 0) {
            visit(visits, FILE_INPUT_STREAM)
                    .and(
                            "FileInputStream.open",
                            Advice.to(SideEffectsAdvice.FileInputStreamOpen.class)
                                    .on(ElementMatchers.named("open")
                                            .and(ElementMatchers.isPrivate())
                                            .and(ElementMatchers.takesArguments(String.class))));
            visit(visits, FILE_OUTPUT_STREAM)
                    .and(
                            "FileOutputStream.open",
                            Advice.to(SideEffectsAdvice.FileOutputStreamOpen.class)
                                    .on(ElementMatchers.named("open")
                                            .and(ElementMatchers.isPrivate())
                                            .and(ElementMatchers.takesArguments(String.class, boolean.class))));
            visit(visits, RANDOM_ACCESS_FILE)
                    .and(
                            "RandomAccessFile.open",
                            Advice.to(SideEffectsAdvice.RandomAccessFileOpen.class)
                                    .on(ElementMatchers.named("open")
                                            .and(ElementMatchers.isPrivate())
                                            .and(ElementMatchers.takesArguments(String.class, int.class))));
            visit(visits, FILES)
                    .and(
                            "Files.newByteChannel",
                            Advice.to(SideEffectsAdvice.NewByteChannel.class)
                                    .on(ElementMatchers.named("newByteChannel")
                                            .and(ElementMatchers.takesArguments(
                                                    Path.class, Set.class, FileAttribute[].class))))
                    .and(
                            "Files.newInputStream",
                            Advice.to(SideEffectsAdvice.NewInputStream.class)
                                    .on(ElementMatchers.named("newInputStream")
                                            .and(ElementMatchers.takesArguments(Path.class, OpenOption[].class))))
                    .and(
                            "Files.newOutputStream",
                            Advice.to(SideEffectsAdvice.NewOutputStream.class)
                                    .on(ElementMatchers.named("newOutputStream")
                                            .and(ElementMatchers.takesArguments(Path.class, OpenOption[].class))))
                    .and(
                            "Files.delete",
                            Advice.to(SideEffectsAdvice.Delete.class)
                                    .on(ElementMatchers.named("delete")
                                            .and(ElementMatchers.takesArguments(Path.class))))
                    .and(
                            "Files.deleteIfExists",
                            Advice.to(SideEffectsAdvice.DeleteIfExists.class)
                                    .on(ElementMatchers.named("deleteIfExists")
                                            .and(ElementMatchers.takesArguments(Path.class))))
                    .and(
                            "Files.move",
                            Advice.to(SideEffectsAdvice.Move.class)
                                    .on(ElementMatchers.named("move")
                                            .and(ElementMatchers.takesArguments(
                                                    Path.class, Path.class, CopyOption[].class))))
                    .and(
                            "Files.copy",
                            Advice.to(SideEffectsAdvice.Copy.class)
                                    .on(ElementMatchers.named("copy")
                                            .and(ElementMatchers.isPublic())
                                            .and(ElementMatchers.takesArguments(
                                                            Path.class, Path.class, CopyOption[].class)
                                                    .or(ElementMatchers.takesArguments(
                                                            InputStream.class, Path.class, CopyOption[].class))
                                                    .or(ElementMatchers.takesArguments(
                                                            Path.class, OutputStream.class)))));
            visit(visits, FILE_CHANNEL)
                    .and(
                            "FileChannel.open",
                            Advice.to(SideEffectsAdvice.FileChannelOpen.class)
                                    .on(ElementMatchers.named("open")
                                            .and(ElementMatchers.takesArguments(
                                                    Path.class, Set.class, FileAttribute[].class))));
        }
        if ((mask & SideEffects.MASK_ENVIRONMENT) != 0) {
            visit(visits, SYSTEM)
                    .and(
                            "System.getenv",
                            Advice.to(SideEffectsAdvice.GetenvName.class)
                                    .on(ElementMatchers.named("getenv")
                                            .and(ElementMatchers.takesArguments(String.class))))
                    .and(
                            "System.getenv",
                            Advice.to(SideEffectsAdvice.GetenvAll.class)
                                    .on(ElementMatchers.named("getenv").and(ElementMatchers.takesArguments(0))))
                    .and(
                            "System.getProperty",
                            Advice.to(SideEffectsAdvice.GetProperty.class)
                                    .on(ElementMatchers.named("getProperty")
                                            .and(ElementMatchers.takesArguments(String.class)
                                                    .or(ElementMatchers.takesArguments(String.class, String.class)))));
        }
        AgentBuilder.Identified.Extendable builder = null;
        AgentBuilder base = stats.configure(new AgentBuilder.Default())
                .assureReadEdgeTo(instrumentation, SideEffects.class)
                .ignore(ElementMatchers.not(ElementMatchers.<TypeDescription>namedOneOf(
                        visits.keySet().toArray(new String[0]))));
        for (Map.Entry<String, ExecutorSensor.Visit> visit : visits.entrySet()) {
            builder = (builder == null ? base : builder)
                    .type(ElementMatchers.named(visit.getKey()))
                    .transform(visit.getValue());
        }
        return builder == null ? base : builder;
    }

    private ExecutorSensor.Visit visit(Map<String, ExecutorSensor.Visit> visits, String type) {
        ExecutorSensor.Visit visit = visits.get(type);
        if (visit == null) {
            visit = new ExecutorSensor.Visit(omitted);
            visits.put(type, visit);
        }
        return visit;
    }

    // ---- self-test -----------------------------------------------------------------------------------------------

    void selfTest(int mask) {
        selfTestPassed = false;
        selfTestError = null;
        state = "testing";
        long started = System.nanoTime();
        Map<String, String> steps = new LinkedHashMap<String, String>();
        Map<String, Object> hits;
        SideEffects.beginSelfTest();
        try {
            if ((mask & SideEffects.MASK_PROCESSES) != 0) {
                steps.put(SideEffects.PROCESSES, processStep());
            }
            if ((mask & SideEffects.MASK_FILES) != 0) {
                steps.put(SideEffects.FILES, filesStep());
            }
            if ((mask & SideEffects.MASK_ENVIRONMENT) != 0) {
                steps.put(SideEffects.ENVIRONMENT, environmentStep());
            }
        } finally {
            hits = SideEffects.endSelfTest();
        }
        selfTestMillis = (System.nanoTime() - started) / 1_000_000L;
        Map<String, String> results = evaluate(mask, hits, steps);
        selfTestSteps = steps;
        List<String> failed = new ArrayList<String>();
        int failedBits = 0;
        for (String[] hook : HOOKS) {
            if ((mask & SideEffects.bit(hook[3])) != 0 && !"passed".equals(results.get(hook[0]))) {
                failed.add(hook[0]);
                failedBits |= SideEffects.bit(hook[3]);
            }
        }
        if (failed.isEmpty()) {
            SideEffects.enable(mask);
            state = "installed";
            selfTestPassed = true;
            selfTest = results;
            return;
        }
        String error = "self-test failed for " + failed + " " + steps;
        // Only the failing sensors stop, for the JVM's life: the others are reinstalled without their hooks.
        SideEffects.disable(failedBits, error);
        failedMask |= failedBits;
        for (String sensor : SENSORS) {
            if ((failedBits & SideEffects.bit(sensor)) != 0) {
                failures.put(sensor, error);
            }
        }
        for (String[] hook : HOOKS) {
            if ((failedBits & SideEffects.bit(hook[3])) != 0) {
                failedHooks.put(hook[0], results.getOrDefault(hook[0], "failed"));
            }
        }
        AgentBridge.message("the BootUI agent's side-effect sensors failed their self-test and were removed: " + error);
        state = "self-test-failed";
        selfTestError = error;
        selfTest = results;
        int remaining = mask & ~failedBits;
        SideEffects.disable(remaining, null);
        reset();
        if (stuck) {
            state = "self-test-failed (release-failed)";
            return;
        }
        synchronized (this) {
            wantedMask &= ~failedBits;
        }
        if (remaining != 0) {
            selfTestError = null;
            install(remaining);
            selfTest(remaining);
            return;
        }
        state = "self-test-failed";
    }

    /** Starts a command {@code ProcessBuilder} refuses before spawning anything: the hook runs, nothing starts. */
    static String processStep() {
        try {
            Process process = new ProcessBuilder(SELF_TEST_COMMAND).start();
            process.destroyForcibly();
            return "error: a command holding a NUL character started";
        } catch (IOException expected) {
            return "ok";
        } catch (Throwable ex) {
            return "error: " + ex;
        }
    }

    /**
     * Opens, deletes, moves, and copies paths under a directory that does not exist, in the temporary directory: each
     * files hook runs and fails before anything is created.
     */
    static String filesStep() {
        String temporary = System.getProperty("java.io.tmpdir");
        return withoutTemporaryDirectory(filesStep(temporary), temporary);
    }

    private static String filesStep(String temporary) {
        File missing = new File(temporary, "bootui-agent-self-test-missing-" + System.nanoTime());
        if (missing.exists()) {
            return "error: " + missing + " exists";
        }
        Path file = missing.toPath().resolve("file");
        Path other = missing.toPath().resolve("other");
        String name = file.toString();
        List<String> unexpected = new ArrayList<String>();
        expectFailure(unexpected, "FileInputStream", () -> new FileInputStream(name).close());
        expectFailure(unexpected, "FileOutputStream", () -> new FileOutputStream(name).close());
        expectFailure(unexpected, "RandomAccessFile", () -> new RandomAccessFile(name, "r").close());
        expectFailure(
                unexpected, "newByteChannel", () -> Files.newByteChannel(file).close());
        expectFailure(
                unexpected, "newInputStream", () -> Files.newInputStream(file).close());
        expectFailure(
                unexpected, "newOutputStream", () -> Files.newOutputStream(file).close());
        expectFailure(unexpected, "delete", () -> Files.delete(file));
        expectFailure(unexpected, "move", () -> Files.move(file, other));
        expectFailure(unexpected, "copy", () -> Files.copy(file, other));
        expectFailure(unexpected, "copy from a stream", () -> Files.copy(new ByteArrayInputStream(new byte[0]), file));
        expectFailure(unexpected, "copy to a stream", () -> Files.copy(file, new ByteArrayOutputStream()));
        expectFailure(
                unexpected,
                "FileChannel.open",
                () -> FileChannel.open(file, StandardOpenOption.READ).close());
        try {
            if (Files.deleteIfExists(file)) {
                unexpected.add("deleteIfExists deleted " + file);
            }
        } catch (IOException ex) {
            unexpected.add("deleteIfExists: " + ex);
        }
        if (missing.exists()) {
            unexpected.add(missing + " was created");
        }
        return unexpected.isEmpty() ? "ok" : "error: " + unexpected;
    }

    /**
     * {@code text}, a self-test result reported by the Java Agent panel, MCP, and the log, with the temporary
     * directory, which holds a user name on Windows and macOS, as {@code $TMPDIR}, in every form a path or an
     * exception message may name it.
     */
    static String withoutTemporaryDirectory(String text, String temporary) {
        if (text == null || temporary == null || temporary.isEmpty()) {
            return text;
        }
        List<String> forms = new ArrayList<String>();
        forms.add(temporary);
        try {
            forms.add(new File(temporary).getCanonicalPath());
            forms.add(new File(temporary).getAbsolutePath());
        } catch (IOException | RuntimeException ex) {
            // The literal form only.
        }
        String result = text;
        for (String form : new ArrayList<String>(forms)) {
            forms.add(form.replace('\\', '/'));
            forms.add(form.replace('/', '\\'));
        }
        // The longest first, so /private/var/... is replaced whole before /var/....
        forms.sort((a, b) -> b.length() - a.length());
        for (String form : forms) {
            String trimmed = form;
            while (trimmed.length() > 1 && (trimmed.endsWith("/") || trimmed.endsWith("\\"))) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            if (trimmed.length() > 1) {
                result = result.replace(trimmed, "$TMPDIR");
            }
        }
        return result;
    }

    interface FileStep {
        void run() throws IOException;
    }

    private static void expectFailure(List<String> unexpected, String step, FileStep action) {
        try {
            action.run();
            unexpected.add(step + " succeeded");
        } catch (IOException expected) {
            // Nothing exists there: the hook ran, then the JDK refused.
        } catch (Throwable ex) {
            unexpected.add(step + ": " + ex);
        }
    }

    /** Reads a variable and a property no one sets, with and without a default, and every variable. */
    static String environmentStep() {
        try {
            System.getenv(SELF_TEST_NAME);
            System.getenv().size();
            System.getProperty(SELF_TEST_PROPERTY);
            System.getProperty(SELF_TEST_PROPERTY, "unset");
            return "ok";
        } catch (Throwable ex) {
            return "error: " + ex;
        }
    }

    static Map<String, String> evaluate(int mask, Map<String, Object> hits, Map<String, String> steps) {
        Map<String, String> results = new LinkedHashMap<String, String>();
        for (String[] hook : HOOKS) {
            if ((mask & SideEffects.bit(hook[3])) == 0) {
                results.put(hook[0], "not-installed");
                continue;
            }
            Object count = hits == null ? null : hits.get(hook[0]);
            String outcome = steps.get(hook[3]);
            if (count instanceof Long && (Long) count > 0) {
                results.put(hook[0], "passed");
            } else {
                results.put(hook[0], "ok".equals(outcome) ? "failed" : "not-exercised (" + outcome + ")");
            }
        }
        return results;
    }

    // ---- status ----------------------------------------------------------------------------------------------------

    /** One status row per side-effect sensor a claim asked for since the JVM started. */
    List<Map<String, Object>> status() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (String id : SENSORS) {
            int bit = SideEffects.bit(id);
            if ((reportedMask & bit) == 0) {
                continue;
            }
            boolean failedHere = (failedMask & bit) != 0;
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("id", id);
            map.put("state", failedHere ? "self-test-failed" : state);
            map.put("idle", Boolean.valueOf(idle()));
            map.put("durationMillis", Long.valueOf(ExecutorSensor.durationMillis(installMillis, selfTestMillis)));
            map.put("installMillis", Long.valueOf(installMillis));
            map.put("selfTestMillis", Long.valueOf(selfTestMillis));
            map.put("selfTestPassed", Boolean.valueOf(!failedHere && selfTestPassed && (installedMask & bit) != 0));
            map.put("selfTestError", failedHere ? failures.get(id) : selfTestError);
            map.put("selfTestSteps", new LinkedHashMap<String, String>(selfTestSteps));
            List<Object> hooks = new ArrayList<Object>();
            Map<String, String> results = selfTest;
            for (String[] hook : HOOKS) {
                if (!id.equals(hook[3])) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("id", hook[0]);
                row.put("kind", hook[2]);
                row.put("type", hook[1]);
                row.put("present", Boolean.valueOf(ExecutorSensor.present(hook[1])));
                row.put("transformed", Boolean.valueOf((installedMask & bit) != 0 && stats.transformed(hook[1])));
                row.put(
                        "selfTest",
                        failedHere
                                ? failedHooks.getOrDefault(hook[0], "failed")
                                : results.getOrDefault(hook[0], "not-run"));
                hooks.add(row);
            }
            map.put("hooks", hooks);
            stats.putInto(map);
            rows.add(map);
        }
        return rows;
    }
}
