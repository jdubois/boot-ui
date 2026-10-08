package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.Resources;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.instrument.Instrumentation;
import java.net.Proxy;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * The {@value SideEffects#RESOURCES} sensor's agent side (PLAN-v2 §5.16, M5-5g, D46): its own transformer of close
 * hooks, apart from the other side-effect sensors', so switching {@code files} or {@code environment} never removes a
 * close hook while the bridge tracks a resource ({@link Resources}). Its opens are the {@code files} and {@code
 * network} sensors' hooks.
 *
 * <p>Advises at exit {@code FileInputStream.close()}, {@code FileOutputStream.close()}, {@code
 * RandomAccessFile.close()}, {@code Socket.close()}, and {@code implCloseChannel()} of {@code sun.nio.ch.FileChannelImpl}
 * and {@code AbstractSelectableChannel}, and at entry {@code FileChannelImpl.setUninterruptible()}.
 *
 * <p><b>Self-test</b> on its worker, each close run once and counted, never recorded: streams over an invalid file
 * descriptor and the channel of one, an unconnected socket, a socket channel opened and closed without any I/O, and the
 * JDK's own {@code release} file opened read-only, never a file created. A resource kind is tracked only once its close
 * hook passed ({@link Resources#enableKinds}), so a missing close hook never makes a resource of its kind look
 * reclaimed; the sensor fails when no close hook passed.
 */
final class ResourcesSensor {

    static final String FILE_INPUT_STREAM = "java.io.FileInputStream";
    static final String FILE_OUTPUT_STREAM = "java.io.FileOutputStream";
    static final String RANDOM_ACCESS_FILE = "java.io.RandomAccessFile";
    static final String FILE_CHANNEL = "sun.nio.ch.FileChannelImpl";
    static final String SOCKET = "java.net.Socket";
    static final String SELECTABLE_CHANNEL = "java.nio.channels.spi.AbstractSelectableChannel";

    /** The type each hook of {@link Resources#HOOKS} transforms, by index. */
    static final String[] HOOK_TYPES = {
        FILE_INPUT_STREAM,
        FILE_OUTPUT_STREAM,
        RANDOM_ACCESS_FILE,
        FILE_CHANNEL,
        SOCKET,
        SELECTABLE_CHANNEL,
        FILE_CHANNEL
    };

    private static final int INSTALL = 1;
    private static final int RELEASE = 2;

    private final Instrumentation instrumentation;
    private final boolean privileged;
    private final Set<String> omitted;
    private final TransformStats stats = new TransformStats();

    private volatile ResettableClassFileTransformer transformer;
    private volatile String state = "off";
    private volatile long installMillis = -1;
    private volatile long selfTestMillis = -1;
    private volatile boolean selfTestPassed;
    private volatile boolean failed;
    private volatile boolean stuck;
    private volatile String selfTestError;
    private volatile int kinds;
    private volatile Map<String, String> selfTest = new LinkedHashMap<String, String>();
    private volatile Map<String, String> steps = new LinkedHashMap<String, String>();
    private volatile boolean reported;
    private boolean wanted;
    private Thread worker;
    private int pending;

    ResourcesSensor(Instrumentation instrumentation, boolean privileged, Set<String> omitted) {
        this.instrumentation = instrumentation;
        this.privileged = privileged;
        this.omitted = omitted == null ? Collections.<String>emptySet() : omitted;
    }

    /** A claim asks for the sensor: installed and self-tested once, then enabled for each claim while installed. */
    synchronized void claimed() {
        wanted = true;
        reported = true;
        if (failed || stuck) {
            return;
        }
        if (transformer != null && selfTestPassed && worker == null) {
            Resources.enableKinds(kinds);
            SideEffects.enable(SideEffects.MASK_RESOURCES);
            return;
        }
        schedule(INSTALL);
    }

    /**
     * A claim without the sensor, or a release: it stops tracking at once, everything it tracked forgotten, then its
     * close hooks are removed off the caller's thread.
     */
    synchronized void release() {
        wanted = false;
        SideEffects.disable(SideEffects.MASK_RESOURCES, null);
        Resources.enableKinds(0);
        if (transformer != null || worker != null) {
            schedule(RELEASE);
        }
    }

    private void schedule(int job) {
        pending = job;
        if (worker == null) {
            worker = AgentThreads.newThread("bootui-agent-resources", new Worker(), privileged);
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

    private synchronized boolean wanted() {
        return wanted;
    }

    /** Whether the worker has no job left. */
    synchronized boolean idle() {
        return worker == null && pending == 0;
    }

    final class Worker implements Runnable {

        @Override
        public void run() {
            int job;
            while ((job = nextJob()) != 0) {
                try {
                    if (job == RELEASE) {
                        reset();
                    } else if (wanted() && !failed && !stuck) {
                        if (transformer == null) {
                            install();
                            selfTest();
                        }
                        enableIfWanted();
                    }
                } catch (Throwable ex) {
                    fail("resources sensor error: " + ex);
                }
            }
        }
    }

    /** Enables the sensor when the claim still wants it and its self-test passed; never after a release. */
    private synchronized void enableIfWanted() {
        if (wanted && selfTestPassed && transformer != null && pending != RELEASE) {
            Resources.enableKinds(kinds);
            SideEffects.enable(SideEffects.MASK_RESOURCES);
        }
    }

    void install() {
        long started = System.nanoTime();
        state = "installing";
        selfTestMillis = -1;
        selfTestPassed = false;
        Resources.warm();
        try {
            InstallAction action = new InstallAction();
            transformer = privileged ? (ResettableClassFileTransformer) AgentThreads.privileged(action) : action.run();
        } finally {
            long elapsed = System.nanoTime() - started;
            stats.retransformedFor(elapsed);
            installMillis = elapsed / 1_000_000L;
        }
        state = "testing";
    }

    final class InstallAction implements PrivilegedAction<ResettableClassFileTransformer> {

        @Override
        public ResettableClassFileTransformer run() {
            return builder().installOn(instrumentation);
        }
    }

    private AgentBuilder builder() {
        Map<String, ExecutorSensor.Visit> visits = new LinkedHashMap<String, ExecutorSensor.Visit>();
        visit(visits, FILE_INPUT_STREAM, 0, Advice.to(ResourcesAdvice.FileInputStreamClose.class), "close");
        visit(visits, FILE_OUTPUT_STREAM, 1, Advice.to(ResourcesAdvice.FileOutputStreamClose.class), "close");
        visit(visits, RANDOM_ACCESS_FILE, 2, Advice.to(ResourcesAdvice.RandomAccessFileClose.class), "close");
        visit(visits, FILE_CHANNEL, 3, Advice.to(ResourcesAdvice.FileChannelClose.class), "implCloseChannel");
        visit(visits, SOCKET, 4, Advice.to(ResourcesAdvice.SocketClose.class), "close");
        visit(
                visits,
                SELECTABLE_CHANNEL,
                5,
                Advice.to(ResourcesAdvice.SelectableChannelClose.class),
                "implCloseChannel");
        visit(visits, FILE_CHANNEL, 6, Advice.to(ResourcesAdvice.Uninterruptible.class), "setUninterruptible");
        List<String> types = new ArrayList<String>(visits.keySet());
        AgentBuilder builder = stats.configure(new AgentBuilder.Default())
                .assureReadEdgeTo(instrumentation, Resources.class)
                .ignore(ElementMatchers.not(ElementMatchers.<TypeDescription>namedOneOf(types.toArray(new String[0]))));
        for (String type : types) {
            builder = builder.type(ElementMatchers.named(type)).transform(visits.get(type));
        }
        return builder;
    }

    private void visit(Map<String, ExecutorSensor.Visit> visits, String type, int hook, Advice advice, String method) {
        ExecutorSensor.Visit visit = visits.get(type);
        if (visit == null) {
            visit = new ExecutorSensor.Visit(omitted);
            visits.put(type, visit);
        }
        visit.and(
                Resources.HOOKS[hook],
                advice.on(ElementMatchers.named(method)
                        .and(ElementMatchers.takesArguments(0))
                        .and(ElementMatchers.not(ElementMatchers.isAbstract()))));
    }

    /** Removes the transformer, restoring the classes it transformed. */
    void reset() {
        ResettableClassFileTransformer installed;
        synchronized (this) {
            installed = transformer;
            transformer = null;
            selfTestPassed = false;
        }
        if (installed == null) {
            state = stuck ? "release-failed" : failed ? state : "released";
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
            // The close hooks stay in the JDK's classes: the sensor stays off for good, and they find nothing tracked.
            stuck = true;
            SideEffects.disable(SideEffects.MASK_RESOURCES, "the resources sensor's transformer could not be removed");
            state = "release-failed";
            return;
        }
        if (!failed) {
            state = "released";
        }
    }

    // ---- self-test -----------------------------------------------------------------------------------------------

    void selfTest() {
        long started = System.nanoTime();
        Map<String, String> outcome = new LinkedHashMap<String, String>();
        Map<String, Object> hits;
        Resources.beginSelfTest();
        try {
            outcome.put("streams", streamsStep());
            outcome.put("socket", socketStep());
            outcome.put("socketChannel", socketChannelStep());
            outcome.put("javaHomeFile", javaHomeStep());
        } finally {
            hits = Resources.endSelfTest();
        }
        selfTestMillis = (System.nanoTime() - started) / 1_000_000L;
        Map<String, String> results = new LinkedHashMap<String, String>();
        int passed = 0;
        List<String> failedHooks = new ArrayList<String>();
        for (int i = 0; i < Resources.HOOKS.length; i++) {
            String hook = Resources.HOOKS[i];
            Object count = hits.get(hook);
            boolean hit = count instanceof Long && ((Long) count).longValue() > 0L && !omitted.contains(hook);
            results.put(hook, hit ? "passed" : "failed");
            if (hit) {
                int kind = kindOf(i);
                if (kind != 0) {
                    passed |= 1 << kind;
                }
            } else {
                failedHooks.add(hook);
            }
        }
        selfTest = results;
        steps = outcome;
        kinds = passed;
        if (passed == 0) {
            fail("self-test failed for every close hook " + outcome);
            reset();
            return;
        }
        selfTestPassed = true;
        selfTestError = failedHooks.isEmpty()
                ? null
                : "self-test failed for " + failedHooks + ": their resources are not tracked " + outcome;
        state = "installed";
        if (!failedHooks.isEmpty()) {
            AgentBridge.message("the BootUI agent's resources sensor does not track the resources of " + failedHooks
                    + ", whose hooks failed their self-test");
        }
    }

    /** The kind a close hook closes, 0 for the setUninterruptible hook. */
    static int kindOf(int hook) {
        switch (hook) {
            case Resources.HOOK_FILE_INPUT_STREAM_CLOSE:
                return Resources.KIND_FILE_INPUT_STREAM;
            case Resources.HOOK_FILE_OUTPUT_STREAM_CLOSE:
                return Resources.KIND_FILE_OUTPUT_STREAM;
            case Resources.HOOK_RANDOM_ACCESS_FILE_CLOSE:
                return Resources.KIND_RANDOM_ACCESS_FILE;
            case Resources.HOOK_FILE_CHANNEL_CLOSE:
                return Resources.KIND_FILE_CHANNEL;
            case Resources.HOOK_SOCKET_CLOSE:
                return Resources.KIND_SOCKET;
            case Resources.HOOK_SELECTABLE_CHANNEL_CLOSE:
                return Resources.KIND_SOCKET_CHANNEL;
            default:
                return 0;
        }
    }

    /**
     * Closes a {@code FileInputStream} and a {@code FileOutputStream} over an invalid descriptor, whose close releases
     * nothing, and the channel of another, which returns at once on an invalid descriptor.
     */
    static String streamsStep() {
        try {
            new FileInputStream(new FileDescriptor()).close();
            new FileOutputStream(new FileDescriptor()).close();
            FileChannel channel = new FileInputStream(new FileDescriptor()).getChannel();
            channel.close();
            return "ok";
        } catch (Throwable ex) {
            return "error: " + ex;
        }
    }

    /** Closes a socket never connected nor bound, which has no descriptor. */
    static String socketStep() {
        try {
            new Socket(Proxy.NO_PROXY).close();
            return "ok";
        } catch (Throwable ex) {
            return "error: " + ex;
        }
    }

    /** Opens a socket channel and closes it, without binding or connecting it. */
    static String socketChannelStep() {
        try {
            SocketChannel.open().close();
            return "ok";
        } catch (Throwable ex) {
            return "error: " + ex;
        }
    }

    /**
     * Opens the JDK's own {@code release} file read-only, through {@code RandomAccessFile} and {@code
     * Files.newInputStream}, and closes both: never a file created, so a read-only file system passes.
     */
    static String javaHomeStep() {
        try {
            Path release = new File(System.getProperty("java.home"), "release").toPath();
            if (!Files.isRegularFile(release)) {
                return "skipped: no release file in java.home";
            }
            new RandomAccessFile(release.toFile(), "r").close();
            try (InputStream stream = Files.newInputStream(release)) {
                stream.available();
            }
            return "ok";
        } catch (Throwable ex) {
            return "error: " + ex;
        }
    }

    private void fail(String error) {
        selfTestPassed = false;
        failed = true;
        selfTestError = error;
        state = "self-test-failed";
        Resources.enableKinds(0);
        SideEffects.disable(SideEffects.MASK_RESOURCES, error);
        stats.failure(error);
        AgentBridge.message("the BootUI agent's resources sensor is off: " + error);
    }

    // ---- status ----------------------------------------------------------------------------------------------------

    /** The sensor's status row, once a claim asked for it; {@code null} before. */
    Map<String, Object> status() {
        if (!reported) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("id", SideEffects.RESOURCES);
        boolean wantedNow;
        synchronized (this) {
            wantedNow = wanted;
        }
        String current = state;
        map.put(
                "state",
                !wantedNow && !current.startsWith("self-test-failed") && !current.contains("release-failed")
                        ? "released"
                        : current);
        map.put("idle", Boolean.valueOf(idle()));
        map.put("durationMillis", Long.valueOf(ExecutorSensor.durationMillis(installMillis, selfTestMillis)));
        map.put("installMillis", Long.valueOf(installMillis));
        map.put("selfTestMillis", Long.valueOf(selfTestMillis));
        map.put("selfTestPassed", Boolean.valueOf(selfTestPassed && transformer != null));
        map.put("selfTestError", selfTestError);
        map.put("selfTestSteps", new LinkedHashMap<String, String>(steps));
        List<Object> hooks = new ArrayList<Object>();
        List<String> leftOut = new ArrayList<String>();
        Map<String, String> results = selfTest;
        Set<String> kindNames = new LinkedHashSet<String>();
        for (int i = 0; i < Resources.HOOKS.length; i++) {
            String id = Resources.HOOKS[i];
            boolean omittedHook = omitted.contains(id);
            if (omittedHook) {
                leftOut.add(id);
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", id);
            row.put("kind", i == Resources.HOOK_UNINTERRUPTIBLE ? "hand-off" : "close");
            row.put("type", HOOK_TYPES[i]);
            row.put("present", Boolean.valueOf(ExecutorSensor.present(HOOK_TYPES[i])));
            row.put(
                    "transformed",
                    Boolean.valueOf(transformer != null && !omittedHook && stats.transformed(HOOK_TYPES[i])));
            row.put("selfTest", omittedHook ? "failed" : results.getOrDefault(id, "not-run"));
            hooks.add(row);
            int kind = kindOf(i);
            if (kind != 0 && (kinds & (1 << kind)) != 0) {
                kindNames.add(kindName(kind));
            }
        }
        map.put("hooks", hooks);
        map.put("hooksLeftOut", leftOut);
        map.put("trackedKinds", new ArrayList<String>(kindNames));
        stats.putInto(map);
        return map;
    }

    static String kindName(int kind) {
        switch (kind) {
            case Resources.KIND_FILE_INPUT_STREAM:
                return "file input stream";
            case Resources.KIND_FILE_OUTPUT_STREAM:
                return "file output stream";
            case Resources.KIND_RANDOM_ACCESS_FILE:
                return "random access file";
            case Resources.KIND_FILE_CHANNEL:
                return "file channel";
            case Resources.KIND_SOCKET:
                return "socket";
            default:
                return "socket channel";
        }
    }
}
