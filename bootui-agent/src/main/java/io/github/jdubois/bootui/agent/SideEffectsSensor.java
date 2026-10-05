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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * The side-effect sensors (PLAN-v2 §5.16, M5-5): one transformer for every JDK side-effect hook, installed with the
 * hooks of the sensors the claim asks for, each hook delegating to the bridge ({@link SideEffectsAdvice}), and one mask
 * in the bridge saying which sensors record ({@link SideEffects#enable}). {@value SideEffects#PROCESSES} hooks {@code
 * ProcessBuilder.start(Redirect[])}; {@value SideEffects#NETWORK} (M5-5b) hooks {@code Socket.connect}, {@code
 * SocketChannelImpl.connect}, {@code blockingConnect}, and {@code finishConnect}, {@code DatagramChannelImpl.send},
 * {@code DatagramSocket.send}, and {@code InetAddress.getAddressesFromNameService}; {@value SideEffects#FILES} (M5-5d)
 * hooks the private {@code open} methods of {@code FileInputStream} and {@code FileOutputStream}, its core hooks, and of
 * {@code RandomAccessFile}, the {@code Files} methods that open, delete, move, and copy, and {@code FileChannel.open};
 * the opt-in {@value SideEffects#ENVIRONMENT} (M5-5d) hooks {@code System.getenv(String)}, {@code System.getenv()}, and
 * {@code System.getProperty}, all core.
 *
 * <p>A self-test per hook runs it on the sensor's worker thread, which the bridge counts and never records, without
 * starting a process or sending a byte: a command holding a NUL character, which {@code ProcessBuilder} refuses before
 * spawning; connects and sends to an unresolved address, or to a Unix-domain address on a TCP channel, which the JDK
 * refuses before any I/O, on a socket without a proxy, so no proxy selector is asked; a finish with no connect pending; a send on a closed socket; and, on a helper
 * thread with a bounded wait, a lookup of a spelling of {@code localhost} the JVM's case-sensitive cache does not hold,
 * which the hosts file answers; files opened, deleted, moved, and copied under a directory that does not exist; and a
 * variable and a property no one sets.
 *
 * <p>Failures are isolated. A hook that fails its self-test is left out of the transformer for the JVM's life; when it
 * is one of its sensor's core hooks, the sensor is disabled for the JVM's life instead, and the transformer reinstalled
 * with the remaining sensors, so one sensor never takes another down. A claim asking for another set of side-effect
 * sensors reinstalls the transformer with that set's hooks; a claim asking for none, or a release, removes it.
 */
final class SideEffectsSensor {

    static final String PROCESS_BUILDER = "java.lang.ProcessBuilder";
    static final String SOCKET = "java.net.Socket";
    static final String SOCKET_CHANNEL = "sun.nio.ch.SocketChannelImpl";
    static final String DATAGRAM_CHANNEL = "sun.nio.ch.DatagramChannelImpl";
    static final String DATAGRAM_SOCKET = "java.net.DatagramSocket";
    static final String INET_ADDRESS = "java.net.InetAddress";
    static final String FILE_INPUT_STREAM = "java.io.FileInputStream";
    static final String FILE_OUTPUT_STREAM = "java.io.FileOutputStream";
    static final String RANDOM_ACCESS_FILE = "java.io.RandomAccessFile";
    static final String FILES = "java.nio.file.Files";
    static final String FILE_CHANNEL = "java.nio.channels.FileChannel";
    static final String SYSTEM = "java.lang.System";

    static final String CORE = "core";
    static final String OPTIONAL = "optional";

    /** Every hook: its id, the type it transforms, its kind, its sensor, and whether its sensor needs it. */
    static final String[][] HOOKS = {
        {"ProcessBuilder.start", PROCESS_BUILDER, "record", SideEffects.PROCESSES, CORE},
        {"Socket.connect", SOCKET, "record", SideEffects.NETWORK, CORE},
        {"SocketChannel.connect", SOCKET_CHANNEL, "record", SideEffects.NETWORK, CORE},
        {"SocketChannel.blockingConnect", SOCKET_CHANNEL, "record", SideEffects.NETWORK, OPTIONAL},
        {"SocketChannel.finishConnect", SOCKET_CHANNEL, "record", SideEffects.NETWORK, OPTIONAL},
        {"DatagramChannel.send", DATAGRAM_CHANNEL, "record", SideEffects.NETWORK, OPTIONAL},
        {"DatagramSocket.send", DATAGRAM_SOCKET, "record", SideEffects.NETWORK, OPTIONAL},
        {"InetAddress.lookup", INET_ADDRESS, "record", SideEffects.NETWORK, OPTIONAL},
        {"FileInputStream.open", FILE_INPUT_STREAM, "record", SideEffects.FILES, CORE},
        {"FileOutputStream.open", FILE_OUTPUT_STREAM, "record", SideEffects.FILES, CORE},
        {"RandomAccessFile.open", RANDOM_ACCESS_FILE, "record", SideEffects.FILES, OPTIONAL},
        {"Files.newByteChannel", FILES, "record", SideEffects.FILES, OPTIONAL},
        {"Files.newInputStream", FILES, "record", SideEffects.FILES, OPTIONAL},
        {"Files.newOutputStream", FILES, "record", SideEffects.FILES, OPTIONAL},
        {"Files.delete", FILES, "record", SideEffects.FILES, OPTIONAL},
        {"Files.deleteIfExists", FILES, "record", SideEffects.FILES, OPTIONAL},
        {"Files.move", FILES, "record", SideEffects.FILES, OPTIONAL},
        {"Files.copy", FILES, "record", SideEffects.FILES, OPTIONAL},
        {"FileChannel.open", FILE_CHANNEL, "record", SideEffects.FILES, OPTIONAL},
        {"System.getenv", SYSTEM, "record", SideEffects.ENVIRONMENT, CORE},
        {"System.getenvAll", SYSTEM, "record", SideEffects.ENVIRONMENT, CORE},
        {"System.getProperty", SYSTEM, "record", SideEffects.ENVIRONMENT, CORE}
    };

    /** The side-effect sensors, in status order. */
    static final String[] SENSORS = {
        SideEffects.PROCESSES, SideEffects.NETWORK, SideEffects.FILES, SideEffects.ENVIRONMENT
    };

    /** The variable and property the environment self-test reads, which no one sets. */
    static final String SELF_TEST_NAME = "BOOTUI_AGENT_SELF_TEST_UNSET";

    static final String SELF_TEST_PROPERTY = "bootui.agent.self-test.unset";

    /** The command the processes self-test starts: {@code ProcessBuilder} refuses a NUL before spawning. */
    static final String SELF_TEST_COMMAND = "bootui-agent-self-test\u0000";

    /** The unresolved host the network self-test connects and sends to: the JDK refuses it before any I/O. */
    static final String SELF_TEST_HOST = "bootui-agent-self-test.invalid";

    /** How long the lookup step's helper thread is waited for. */
    static final long LOOKUP_WAIT_MILLIS = 5_000L;

    /** Rounds of self-test at most: each round that fails leaves at least one hook out. */
    private static final int MAX_ROUNDS = 4;

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
    /** Sensors whose core hook failed its self-test: off for the JVM's life. */
    private volatile int failedSensors;
    /** Hooks that failed their self-test: left out of the transformer for the JVM's life. */
    private final Set<String> failedHooks = new LinkedHashSet<String>();
    /** Why each failed sensor failed. */
    private final Map<String, String> sensorErrors = new LinkedHashMap<String, String>();

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
    synchronized void claimed(int mask) {
        wantedMask = mask;
        reportedMask |= mask;
        if (stuck) {
            return;
        }
        int effective = mask & ~failedSensors;
        // Only while no job runs: a release the worker is running would remove the hooks after this enabled them.
        if (transformer != null && installedMask == effective && selfTestPassed && worker == null) {
            SideEffects.enable(effective);
            return;
        }
        if (effective == 0 && transformer == null) {
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

    /** The sensors the current claim asks for that have not failed. */
    private int effective() {
        return wantedMask & ~failedSensors;
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
                int mask = effective();
                try {
                    if ((job & RELEASE) != 0 || (job & INSTALL) != 0 && transformer != null && installedMask != mask) {
                        SideEffects.disable(installedMask, null);
                        reset();
                    }
                    if ((job & INSTALL) != 0 && !stuck && mask != 0) {
                        if (transformer == null) {
                            install(mask);
                        }
                        selfTest(mask, 1);
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
        InstallAction action = new InstallAction(mask, leftOut());
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

    /** The hooks the transformer leaves out: the mutation tests' omissions and the hooks that failed. */
    private synchronized Set<String> leftOut() {
        Set<String> left = new LinkedHashSet<String>(omitted);
        left.addAll(failedHooks);
        return left;
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
        private final Set<String> left;

        InstallAction(int mask, Set<String> left) {
            this.mask = mask;
            this.left = left;
        }

        @Override
        public ResettableClassFileTransformer run() {
            return builder(mask, left).installOn(instrumentation);
        }
    }

    private AgentBuilder builder(int mask, Set<String> left) {
        List<String> types = new ArrayList<String>();
        List<ExecutorSensor.Visit> visits = new ArrayList<ExecutorSensor.Visit>();
        if ((mask & SideEffects.MASK_PROCESSES) != 0) {
            types.add(PROCESS_BUILDER);
            visits.add(new ExecutorSensor.Visit(left)
                    .and(
                            "ProcessBuilder.start",
                            Advice.to(SideEffectsAdvice.ProcessStart.class)
                                    .on(ElementMatchers.named("start")
                                            .and(ElementMatchers.isPrivate())
                                            .and(ElementMatchers.takesArguments(1))
                                            .and(ElementMatchers.takesArgument(0, ProcessBuilder.Redirect[].class)))));
        }
        if ((mask & SideEffects.MASK_NETWORK) != 0) {
            types.add(SOCKET);
            visits.add(new ExecutorSensor.Visit(left)
                    .and(
                            "Socket.connect",
                            Advice.to(SideEffectsAdvice.SocketConnect.class)
                                    .on(ElementMatchers.named("connect")
                                            .and(ElementMatchers.takesArguments(2))
                                            .and(ElementMatchers.takesArgument(0, SocketAddress.class))
                                            .and(ElementMatchers.takesArgument(1, int.class)))));
            types.add(SOCKET_CHANNEL);
            visits.add(new ExecutorSensor.Visit(left)
                    .and(
                            "SocketChannel.connect",
                            Advice.to(SideEffectsAdvice.ChannelConnect.class)
                                    .on(ElementMatchers.named("connect")
                                            .and(ElementMatchers.takesArguments(1))
                                            .and(ElementMatchers.takesArgument(0, SocketAddress.class))
                                            .and(ElementMatchers.returns(boolean.class))))
                    .and(
                            "SocketChannel.blockingConnect",
                            Advice.to(SideEffectsAdvice.ChannelBlockingConnect.class)
                                    .on(ElementMatchers.named("blockingConnect")
                                            .and(ElementMatchers.takesArguments(2))
                                            .and(ElementMatchers.takesArgument(0, SocketAddress.class))
                                            .and(ElementMatchers.takesArgument(1, long.class))))
                    .and(
                            "SocketChannel.finishConnect",
                            Advice.to(SideEffectsAdvice.ChannelFinishConnect.class)
                                    .on(ElementMatchers.named("finishConnect")
                                            .and(ElementMatchers.takesArguments(0))
                                            .and(ElementMatchers.returns(boolean.class)))));
            types.add(DATAGRAM_CHANNEL);
            visits.add(new ExecutorSensor.Visit(left)
                    .and(
                            "DatagramChannel.send",
                            Advice.to(SideEffectsAdvice.DatagramChannelSend.class)
                                    .on(ElementMatchers.named("send")
                                            .and(ElementMatchers.isPublic())
                                            .and(ElementMatchers.takesArguments(2))
                                            .and(ElementMatchers.takesArgument(0, ByteBuffer.class))
                                            .and(ElementMatchers.takesArgument(1, SocketAddress.class)))));
            types.add(DATAGRAM_SOCKET);
            visits.add(new ExecutorSensor.Visit(left)
                    .and(
                            "DatagramSocket.send",
                            Advice.to(SideEffectsAdvice.DatagramSocketSend.class)
                                    .on(ElementMatchers.named("send")
                                            .and(ElementMatchers.takesArguments(1))
                                            .and(ElementMatchers.takesArgument(0, DatagramPacket.class)))));
            types.add(INET_ADDRESS);
            visits.add(new ExecutorSensor.Visit(left)
                    .and(
                            "InetAddress.lookup",
                            Advice.to(SideEffectsAdvice.Lookup.class)
                                    .on(ElementMatchers.named("getAddressesFromNameService")
                                            .and(ElementMatchers.isStatic())
                                            .and(ElementMatchers.takesArgument(0, String.class)))));
        }
        if ((mask & SideEffects.MASK_FILES) != 0) {
            types.add(FILE_INPUT_STREAM);
            visits.add(new ExecutorSensor.Visit(left)
                    .and(
                            "FileInputStream.open",
                            Advice.to(SideEffectsAdvice.FileInputStreamOpen.class)
                                    .on(ElementMatchers.named("open")
                                            .and(ElementMatchers.isPrivate())
                                            .and(ElementMatchers.takesArguments(String.class)))));
            types.add(FILE_OUTPUT_STREAM);
            visits.add(new ExecutorSensor.Visit(left)
                    .and(
                            "FileOutputStream.open",
                            Advice.to(SideEffectsAdvice.FileOutputStreamOpen.class)
                                    .on(ElementMatchers.named("open")
                                            .and(ElementMatchers.isPrivate())
                                            .and(ElementMatchers.takesArguments(String.class, boolean.class)))));
            types.add(RANDOM_ACCESS_FILE);
            visits.add(new ExecutorSensor.Visit(left)
                    .and(
                            "RandomAccessFile.open",
                            Advice.to(SideEffectsAdvice.RandomAccessFileOpen.class)
                                    .on(ElementMatchers.named("open")
                                            .and(ElementMatchers.isPrivate())
                                            .and(ElementMatchers.takesArguments(String.class, int.class)))));
            types.add(FILES);
            visits.add(new ExecutorSensor.Visit(left)
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
                                                            Path.class, OutputStream.class))))));
            types.add(FILE_CHANNEL);
            visits.add(new ExecutorSensor.Visit(left)
                    .and(
                            "FileChannel.open",
                            Advice.to(SideEffectsAdvice.FileChannelOpen.class)
                                    .on(ElementMatchers.named("open")
                                            .and(ElementMatchers.takesArguments(
                                                    Path.class, Set.class, FileAttribute[].class)))));
        }
        if ((mask & SideEffects.MASK_ENVIRONMENT) != 0) {
            types.add(SYSTEM);
            visits.add(new ExecutorSensor.Visit(left)
                    .and(
                            "System.getenv",
                            Advice.to(SideEffectsAdvice.GetenvName.class)
                                    .on(ElementMatchers.named("getenv")
                                            .and(ElementMatchers.takesArguments(String.class))))
                    .and(
                            "System.getenvAll",
                            Advice.to(SideEffectsAdvice.GetenvAll.class)
                                    .on(ElementMatchers.named("getenv").and(ElementMatchers.takesArguments(0))))
                    .and(
                            "System.getProperty",
                            Advice.to(SideEffectsAdvice.GetProperty.class)
                                    .on(ElementMatchers.named("getProperty")
                                            .and(ElementMatchers.takesArguments(String.class)
                                                    .or(ElementMatchers.takesArguments(String.class, String.class))))));
        }
        AgentBuilder builder = stats.configure(new AgentBuilder.Default())
                .assureReadEdgeTo(instrumentation, SideEffects.class)
                .ignore(ElementMatchers.not(ElementMatchers.<TypeDescription>namedOneOf(types.toArray(new String[0]))));
        for (int i = 0; i < types.size(); i++) {
            builder = builder.type(ElementMatchers.named(types.get(i))).transform(visits.get(i));
        }
        return builder;
    }

    // ---- self-test -----------------------------------------------------------------------------------------------

    void selfTest(int mask, int round) {
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
            if ((mask & SideEffects.MASK_NETWORK) != 0) {
                networkSteps(steps, privileged);
            }
            if ((mask & SideEffects.MASK_FILES) != 0) {
                steps.put(SideEffects.FILES, filesStep());
            }
            if ((mask & SideEffects.MASK_ENVIRONMENT) != 0) {
                environmentSteps(steps);
            }
        } finally {
            hits = SideEffects.endSelfTest();
        }
        selfTestMillis = (System.nanoTime() - started) / 1_000_000L;
        Set<String> left = leftOut();
        Map<String, String> results = evaluate(mask, hits, steps, left);
        selfTestSteps = steps;
        List<String> failed = new ArrayList<String>();
        int failedNow = 0;
        for (String[] hook : HOOKS) {
            int bit = SideEffects.bit(hook[3]);
            if ((mask & bit) == 0 || "passed".equals(results.get(hook[0]))) {
                continue;
            }
            if (left.contains(hook[0]) && !CORE.equals(hook[4])) {
                // Left out already: its sensor records without it.
                continue;
            }
            failed.add(hook[0]);
            if (CORE.equals(hook[4])) {
                failedNow |= bit;
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
        synchronized (this) {
            failedHooks.addAll(failed);
            failedSensors |= failedNow;
            for (String id : SENSORS) {
                if ((failedNow & SideEffects.bit(id)) != 0) {
                    sensorErrors.put(id, error);
                }
            }
        }
        if (failedNow != 0) {
            SideEffects.disable(failedNow, error);
            AgentBridge.message(
                    "the BootUI agent's side-effect sensors failed their self-test and were removed: " + error);
        } else {
            AgentBridge.message("the BootUI agent left side-effect hooks out after their self-test failed: " + error);
        }
        // The verdict before the transformer's removal, which takes a while: status reports it at once, the sensors
        // that did not fail reading as installing again.
        state = "self-test-failed";
        selfTestError = error;
        selfTest = results;
        SideEffects.disable(mask & ~failedNow, null);
        reset();
        int remaining = effective();
        if (stuck) {
            state = "self-test-failed (release-failed)";
            return;
        }
        if (remaining != 0 && round < MAX_ROUNDS) {
            // The other sensors, and this one without the hooks that failed, are installed and self-tested again.
            install(remaining);
            selfTest(remaining, round + 1);
            return;
        }
        if (remaining != 0) {
            synchronized (this) {
                failedSensors |= remaining;
                for (String id : SENSORS) {
                    if ((remaining & SideEffects.bit(id)) != 0) {
                        sensorErrors.put(id, error);
                    }
                }
            }
            SideEffects.disable(remaining, error);
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
     * Runs each network hook once without any I/O, its outcome by hook id: connects and sends to an unresolved address,
     * which the JDK refuses before touching the network, on a socket without a proxy; a finish with no connect pending;
     * a send on a closed socket; and a name lookup, on a helper thread waited for at most {@value #LOOKUP_WAIT_MILLIS}
     * ms.
     */
    static void networkSteps(Map<String, String> steps, boolean privileged) {
        InetSocketAddress unresolved = InetSocketAddress.createUnresolved(SELF_TEST_HOST, 9);
        steps.put("Socket.connect", expectRefused(() -> {
            try (Socket socket = new Socket(Proxy.NO_PROXY)) {
                socket.connect(unresolved, 1);
            }
        }));
        steps.put("SocketChannel.connect", expectRefused(() -> {
            try (SocketChannel channel = SocketChannel.open()) {
                channel.connect(unresolved);
            }
        }));
        steps.put("SocketChannel.blockingConnect", expectRefused(() -> {
            // A Unix-domain address on a TCP channel: since JDK 25 the adaptor refuses an unresolved address before
            // blockingConnect, which refuses this one itself, before any I/O.
            try (SocketChannel channel = SocketChannel.open()) {
                channel.socket().connect(java.net.UnixDomainSocketAddress.of(SELF_TEST_HOST), 1);
            }
        }));
        steps.put("SocketChannel.finishConnect", expectRefused(() -> {
            try (SocketChannel channel = SocketChannel.open()) {
                channel.configureBlocking(false);
                channel.finishConnect();
            }
        }));
        steps.put("DatagramChannel.send", expectRefused(() -> {
            try (DatagramChannel channel = DatagramChannel.open()) {
                channel.send(ByteBuffer.allocate(0), unresolved);
            }
        }));
        steps.put("DatagramSocket.send", expectRefused(() -> {
            // Never bound: no port is opened, and the closed socket refuses before sending.
            DatagramSocket socket = new DatagramSocket((SocketAddress) null);
            socket.close();
            socket.send(new DatagramPacket(new byte[0], 0, InetAddress.getLoopbackAddress(), 9));
        }));
        steps.put("InetAddress.lookup", lookupStep(privileged));
    }

    /** A step that must throw: {@code ok} when it did, as the JDK refuses before any I/O. */
    static String expectRefused(Step step) {
        try {
            step.run();
            return "error: the step did not fail";
        } catch (Exception expected) {
            return "ok";
        } catch (Throwable ex) {
            return "error: " + ex;
        }
    }

    /** A step that may throw. */
    interface Step {
        void run() throws Exception;
    }

    /**
     * Resolves two spellings of {@code localhost} the JVM's case-sensitive address cache does not hold, which the hosts
     * file answers, on a helper thread the bridge counts as the self-test's, waited for at most {@value
     * #LOOKUP_WAIT_MILLIS} ms so a slow resolver never stalls the install.
     */
    static String lookupStep(boolean privileged) {
        AtomicReference<String> outcome = new AtomicReference<String>("error: timed out");
        Thread helper = AgentThreads.newThread(
                "bootui-agent-self-test-lookup",
                () -> {
                    for (int i = 0; i < 2; i++) {
                        try {
                            InetAddress.getAllByName(spelling());
                        } catch (IOException resolvedOrNot) {
                            // Unknown or not, the lookup reached the name service.
                        }
                    }
                    outcome.set("ok");
                },
                privileged);
        Thread self = Thread.currentThread();
        SideEffects.selfTestOn(helper);
        try {
            helper.start();
            helper.join(LOOKUP_WAIT_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            SideEffects.selfTestOn(self);
        }
        return outcome.get();
    }

    /** {@code localhost} with a random mix of cases, never all lower case, which the application likely resolved. */
    static String spelling() {
        String name = "localhost";
        int bits = ThreadLocalRandom.current().nextInt(1, 1 << name.length());
        StringBuilder spelled = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            spelled.append((bits & (1 << i)) != 0 ? Character.toUpperCase(c) : c);
        }
        return spelled.toString();
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

    /** Reads a variable and a property no one sets, with and without a default, and every variable: a step per hook. */
    static void environmentSteps(Map<String, String> steps) {
        steps.put("System.getenv", environmentStep(() -> System.getenv(SELF_TEST_NAME)));
        steps.put("System.getenvAll", environmentStep(() -> System.getenv().size()));
        steps.put("System.getProperty", environmentStep(() -> {
            System.getProperty(SELF_TEST_PROPERTY);
            System.getProperty(SELF_TEST_PROPERTY, "unset");
        }));
    }

    private static String environmentStep(Runnable step) {
        try {
            step.run();
            return "ok";
        } catch (Throwable ex) {
            return "error: " + ex;
        }
    }

    static Map<String, String> evaluate(int mask, Map<String, Object> hits, Map<String, String> steps) {
        return evaluate(mask, hits, steps, Set.of());
    }

    /** Each hook's result: {@code passed}, {@code failed}, {@code not-exercised (…)}, or {@code not-installed}. */
    static Map<String, String> evaluate(
            int mask, Map<String, Object> hits, Map<String, String> steps, Set<String> left) {
        Map<String, String> results = new LinkedHashMap<String, String>();
        for (String[] hook : HOOKS) {
            if ((mask & SideEffects.bit(hook[3])) == 0) {
                results.put(hook[0], "not-installed");
                continue;
            }
            Object count = hits == null ? null : hits.get(hook[0]);
            String outcome = steps.containsKey(hook[0]) ? steps.get(hook[0]) : steps.get(hook[3]);
            if (count instanceof Long && (Long) count > 0) {
                results.put(hook[0], "passed");
            } else if (left.contains(hook[0])) {
                results.put(hook[0], "failed");
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
        Set<String> left = leftOut();
        for (String id : SENSORS) {
            int bit = SideEffects.bit(id);
            if ((reportedMask & bit) == 0) {
                continue;
            }
            boolean failed = (failedSensors & bit) != 0;
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("id", id);
            map.put(
                    "state",
                    failed ? (stuck ? "self-test-failed (release-failed)" : "self-test-failed") : sensorState(bit));
            map.put("idle", Boolean.valueOf(idle()));
            map.put("durationMillis", Long.valueOf(ExecutorSensor.durationMillis(installMillis, selfTestMillis)));
            map.put("installMillis", Long.valueOf(installMillis));
            map.put("selfTestMillis", Long.valueOf(selfTestMillis));
            map.put("selfTestPassed", Boolean.valueOf(!failed && selfTestPassed && (installedMask & bit) != 0));
            String error;
            synchronized (this) {
                error = failed ? sensorErrors.get(id) : null;
            }
            map.put("selfTestError", error);
            map.put("selfTestSteps", new LinkedHashMap<String, String>(selfTestSteps));
            List<Object> hooks = new ArrayList<Object>();
            List<String> leftOut = new ArrayList<String>();
            Map<String, String> results = selfTest;
            for (String[] hook : HOOKS) {
                if (!id.equals(hook[3])) {
                    continue;
                }
                boolean omittedHook = left.contains(hook[0]);
                if (omittedHook && !failed) {
                    leftOut.add(hook[0]);
                }
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("id", hook[0]);
                row.put("kind", hook[2]);
                row.put("type", hook[1]);
                row.put("present", Boolean.valueOf(ExecutorSensor.present(hook[1])));
                row.put(
                        "transformed",
                        Boolean.valueOf((installedMask & bit) != 0 && !omittedHook && stats.transformed(hook[1])));
                row.put("selfTest", omittedHook ? "failed" : results.getOrDefault(hook[0], "not-run"));
                hooks.add(row);
            }
            map.put("hooks", hooks);
            map.put("hooksLeftOut", leftOut);
            stats.putInto(map);
            rows.add(map);
        }
        return rows;
    }

    /** A sensor's state while it has not failed: the transformer's, or {@code released} when it carries another set. */
    private String sensorState(int bit) {
        String current = state;
        if ("installed".equals(current) && (installedMask & bit) == 0) {
            return "released";
        }
        if (current.startsWith("self-test-failed")) {
            // Another sensor's failure: this one is installed again without it.
            return stuck ? "release-failed" : "installing";
        }
        return current;
    }
}
