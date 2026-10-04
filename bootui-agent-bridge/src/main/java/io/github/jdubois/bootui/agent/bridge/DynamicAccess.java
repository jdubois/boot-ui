package io.github.jdubois.bootui.agent.bridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The {@code dynamic-access} sensor's recorder (PLAN-v2 §5.15, M5-9b spike, experimental): a user-triggered recording
 * session, bounded in time and in distinct entries, of the reflection, proxies, and (count-only) other dynamic accesses
 * an application makes, each attributed to the class that called the JDK method, as GraalVM's tracing agent filters by
 * its caller. A call whose immediate caller is the JDK (boot or platform class loader) or tooling is counted, never
 * recorded. Only what native-image metadata needs is kept: type names, member names, and parameter types, never an
 * argument value: a failed {@code Class.forName} is only counted, since its name is the caller's string. A generated
 * caller (a JDK proxy's static initializer, a CGLIB or AOT subclass) is counted too: native-image metadata for it comes
 * with the proxy or from the framework's own build-time processing.
 *
 * <p>With no session running, an advised method pays one volatile read of {@link #on}. The recorder runs inside advised
 * {@code java.lang.Class}, {@code Method}, {@code Constructor}, and {@code Proxy} methods, so it follows the bridge
 * rules, never throws, and guards itself against the accesses its own work makes.
 */
public final class DynamicAccess {

    public static final String SENSOR = "dynamic-access";

    public static final String RECORDING = "recording";
    public static final String RUNNING = "running";
    public static final String STOPPED = "stopped";
    public static final String NONE = "none";

    public static final int FOR_NAME = 0;
    public static final int INVOKE = 1;
    public static final int NEW_INSTANCE = 2;
    public static final int PROXY = 3;

    private static final String[] KINDS = {"forName", "invoke", "newInstance", "proxy"};

    /** Every hook: its id, its kind's index or -1 for a count-only hook, and the advised type. */
    public static final int HOOK_FOR_NAME = 0;

    public static final int HOOK_FOR_NAME_LOADER = 1;
    public static final int HOOK_FOR_NAME_MODULE = 2;
    public static final int HOOK_INVOKE = 3;
    public static final int HOOK_NEW_INSTANCE = 4;
    public static final int HOOK_NEW_PROXY_INSTANCE = 5;
    public static final int HOOK_METHODS = 6;
    public static final int HOOK_CONSTRUCTORS = 7;
    public static final int HOOK_FIELDS = 8;
    public static final int HOOK_FIELD_VALUE = 9;
    public static final int HOOK_CLASS_RESOURCE = 10;
    public static final int HOOK_LOADER_RESOURCE = 11;
    public static final int HOOK_RESOLVE_CLASS = 12;
    public static final int HOOK_DESERIALIZED = 13;
    public static final int HOOK_PROXY_CLASS = 14;

    private static final String[] HOOKS = {
        "Class.forName(String)",
        "Class.forName(String,boolean,ClassLoader)",
        "Class.forName(Module,String)",
        "Method.invoke",
        "Constructor.newInstance",
        "Proxy.newProxyInstance",
        "Class.get*Method*",
        "Class.get*Constructor*",
        "Class.get*Field*",
        "Field.get/set",
        "Class.getResource*",
        "ClassLoader.getResource*",
        "ObjectInputStream.resolveClass",
        "ObjectStreamClass.initNonProxy",
        "Proxy.getProxyClass"
    };

    /** The advised method of each kind, whose frame, when the stack walk shows it, is skipped to reach its caller. */
    private static final String[] OWNERS = {
        "java.lang.Class", "java.lang.reflect.Method", "java.lang.reflect.Constructor", "java.lang.reflect.Proxy"
    };

    private static final String[] METHODS = {"forName", "invoke", "newInstance", "newProxyInstance"};

    /**
     * Callers that are tooling, not the application or its dependencies: BootUI, other agents, IDE runners. Byte Buddy's
     * agent package is built at run time: the shade plugin rewrites every {@code net.bytebuddy} constant of the agent
     * jar, the bridge's included, to its relocated package.
     */
    private static final String[] TOOLING = {
        "io.github.jdubois.bootui.agent.",
        "io.github.jdubois.bootui.engine.",
        "io.github.jdubois.bootui.core.",
        "io.github.jdubois.bootui.spi.",
        "io.github.jdubois.bootui.autoconfigure.",
        "io.github.jdubois.bootui.quarkus.",
        "io.opentelemetry.javaagent.",
        "com.intellij.rt.",
        "org.jacoco.agent.rt.",
        new StringBuilder("net.").append("bytebuddy.agent.").toString()
    };

    private static final String BRIDGE = "io.github.jdubois.bootui.agent.bridge.";

    public static final int DEFAULT_SECONDS = 60;
    public static final int MAX_SECONDS = 600;
    public static final int DEFAULT_ENTRIES = 2_000;
    public static final int MAX_ENTRIES = 10_000;
    /** Frames walked at most per recorded access: the caller, then the first application frame above it. */
    static final int FRAMES = 64;
    /** Longest recorded type name or proxy interface list; a longer one is counted as oversized. */
    static final int MAX_NAME = 2_048;

    private static final ClassLoader PLATFORM = ClassLoader.getPlatformClassLoader();

    /** Read by every advised call: with no session and no self-test, the whole cost of the sensor. */
    public static volatile boolean on;

    private static volatile boolean selfTesting;
    private static volatile boolean installed;
    private static volatile String unavailableReason = "the dynamic-access sensor is not installed";
    private static final AtomicReference<Session> SESSION = new AtomicReference<Session>();
    /** The last ended session of the current claim, its entries built only when read. */
    private static final AtomicReference<Session> LAST = new AtomicReference<Session>();
    /** A session the current claim asked to start as soon as the sensor is installed, to see startup's accesses. */
    private static final AtomicReference<Startup> STARTUP = new AtomicReference<Startup>();

    private static final AtomicLongArray SELF_TEST_HITS = new AtomicLongArray(HOOKS.length);
    private static final LongAdder ERRORS = new LongAdder();

    private DynamicAccess() {}

    // ---- advice entry points: called only when on is true -----------------------------------------------------------

    /** {@code Class.forName} returned {@code type}. */
    public static void forName(Class<?> type, int hook) {
        record(hook, FOR_NAME, type);
    }

    /** {@code Class.forName} threw: counted, never recorded, since the name is the caller's string. */
    public static void forNameFailed(int hook) {
        try {
            if (Reentrancy.dynamicSelfTest()) {
                SELF_TEST_HITS.incrementAndGet(hook);
                return;
            }
            Session session = SESSION.get();
            if (session != null && !Reentrancy.inDynamic()) {
                session.hits.incrementAndGet(hook);
                session.failedLookups.increment();
            }
        } catch (Throwable ex) {
            error(ex);
        }
    }

    /** {@code Method.invoke} on {@code method}, before it runs; never its receiver or arguments. */
    public static void invoke(Method method) {
        record(HOOK_INVOKE, INVOKE, method);
    }

    /** {@code Constructor.newInstance} on {@code constructor}, before it runs; never its arguments. */
    public static void newInstance(Constructor<?> constructor) {
        record(HOOK_NEW_INSTANCE, NEW_INSTANCE, constructor);
    }

    /** {@code Proxy.newProxyInstance} for {@code interfaces}, before it runs; never its handler. */
    public static void proxy(Class<?>[] interfaces) {
        record(HOOK_NEW_PROXY_INSTANCE, PROXY, interfaces);
    }

    /** A count-only hook ran: the spike's evidence that the method can be advised, never attributed. */
    public static void hit(int hook) {
        try {
            if (Reentrancy.dynamicSelfTest()) {
                SELF_TEST_HITS.incrementAndGet(hook);
                return;
            }
            Session session = SESSION.get();
            if (session != null && !Reentrancy.inDynamic()) {
                session.hits.incrementAndGet(hook);
            }
        } catch (Throwable ex) {
            error(ex);
        }
    }

    private static void record(int hook, int kind, Object subject) {
        try {
            if (Reentrancy.dynamicSelfTest()) {
                SELF_TEST_HITS.incrementAndGet(hook);
                return;
            }
            Session session = SESSION.get();
            if (session == null) {
                return;
            }
            if (System.nanoTime() - session.deadline >= 0L) {
                end(session, "time limit");
                return;
            }
            if (!Reentrancy.enterDynamic()) {
                // The recorder's own work: StackWalker creates its frames with Constructor.newInstance.
                return;
            }
            try {
                session.hits.incrementAndGet(hook);
                if (Reentrancy.bootUiWork()) {
                    session.bootUiWork.increment();
                    return;
                }
                session.record(kind, subject);
            } finally {
                Reentrancy.exitDynamic();
            }
        } catch (Throwable ex) {
            error(ex);
        }
    }

    // ---- sessions
    // ----------------------------------------------------------------------------------------------------

    /**
     * Starts a recording session for the armed claim holding {@code token}, which must have asked for the sensor:
     * {@code options} may carry {@code seconds} (1–{@value #MAX_SECONDS}, default {@value #DEFAULT_SECONDS}) and
     * {@code maxEntries} (1–{@value #MAX_ENTRIES}, default {@value #DEFAULT_ENTRIES}). One session at a time.
     */
    public static Map<String, Object> start(long token, Map<String, ?> options) {
        try {
            Claim claim = AgentBridge.current();
            if (claim == null || !claim.armed || claim.token != token) {
                return answer(AgentBridge.STALE, "this claim was replaced or ended");
            }
            if (!installed || !claim.hasSensor(SENSOR)) {
                return answer(AgentBridge.UNAVAILABLE, unavailableReason);
            }
            int seconds = bounded(options == null ? null : options.get("seconds"), DEFAULT_SECONDS, MAX_SECONDS);
            int maxEntries = bounded(options == null ? null : options.get("maxEntries"), DEFAULT_ENTRIES, MAX_ENTRIES);
            Session session = new Session(token, seconds, maxEntries, packages(claim.packages));
            warm(session);
            if (!SESSION.compareAndSet(null, session)) {
                return answer(RUNNING, "a recording session is already running");
            }
            refresh();
            Claim now = AgentBridge.current();
            if (!installed || now == null || !now.armed || now.token != token) {
                // A release or a new claim landed between the checks and the session's start.
                end(session, "run ended");
                return answer(AgentBridge.STALE, "this claim was replaced or ended");
            }
            Map<String, Object> map = answer(RECORDING, null);
            map.put("seconds", Integer.valueOf(seconds));
            map.put("maxEntries", Integer.valueOf(maxEntries));
            return map;
        } catch (Throwable ex) {
            error(ex);
            return answer(AgentBridge.FAILED, String.valueOf(ex));
        }
    }

    /** Stops the session of the claim holding {@code token} and returns what it recorded. */
    public static Map<String, Object> stop(long token) {
        try {
            Session session = SESSION.get();
            if (session != null && session.token == token) {
                end(session, STOPPED);
            }
            Session last = LAST.get();
            boolean mine = last != null && last.token == token;
            Map<String, Object> map = answer(mine ? STOPPED : NONE, null);
            map.put("session", mine ? last.result(true) : null);
            return map;
        } catch (Throwable ex) {
            error(ex);
            return answer(AgentBridge.FAILED, String.valueOf(ex));
        }
    }

    /** The sensor's state, the running session's counters, and the last ended session with its entries. */
    public static Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("installed", Boolean.valueOf(installed));
        map.put("reason", installed ? null : unavailableReason);
        map.put("on", Boolean.valueOf(on));
        map.put("errors", Long.valueOf(ERRORS.sum()));
        try {
            Session session = SESSION.get();
            if (session != null && System.nanoTime() - session.deadline >= 0L) {
                end(session, "time limit");
                session = null;
            }
            map.put("session", session == null ? null : session.result(false));
            Session last = LAST.get();
            // Counts only: the entries go to the claim that recorded them, through stop(token).
            map.put("last", last == null ? null : last.result(false));
        } catch (Throwable ex) {
            error(ex);
        }
        return map;
    }

    /**
     * A new claim (or none, on a release): ends any session, forgets the last one, which belonged to the previous run,
     * and keeps the new claim's {@code dynamicAccess.startupSeconds} and {@code maxEntries} request, if any, for when the
     * sensor is installed, or starts that session now when it already is.
     */
    static void claimed(Claim claim, Object options) {
        try {
            endAll("run ended");
            LAST.set(null);
            STARTUP.set(null);
            if (claim == null || !(options instanceof Map) || !claim.hasSensor(SENSOR)) {
                return;
            }
            Object seconds = ((Map<?, ?>) options).get("startupSeconds");
            if (!(seconds instanceof Number) || ((Number) seconds).intValue() <= 0) {
                return;
            }
            Map<String, Object> request = new LinkedHashMap<String, Object>();
            request.put("seconds", seconds);
            request.put("maxEntries", ((Map<?, ?>) options).get("maxEntries"));
            STARTUP.set(new Startup(claim.token, request));
            if (installed) {
                startPending();
            }
        } catch (Throwable ex) {
            error(ex);
        }
    }

    private static void startPending() {
        Startup startup = STARTUP.getAndSet(null);
        if (startup != null) {
            start(startup.token, startup.options);
        }
    }

    /** Ends any session: the run ended, the claim changed, or the sensor is being removed. */
    static void endAll(String reason) {
        try {
            Session session = SESSION.get();
            if (session != null) {
                end(session, reason);
            }
        } catch (Throwable ex) {
            error(ex);
        }
    }

    private static void end(Session session, String reason) {
        if (SESSION.compareAndSet(session, null)) {
            session.closed = true;
            session.endReason = reason;
            session.endedMillis = System.currentTimeMillis();
            refresh();
            LAST.set(session);
        }
    }

    /** Recomputes {@link #on}, looping so a racing start or end never leaves it stale. */
    private static void refresh() {
        boolean value;
        do {
            value = selfTesting || SESSION.get() != null;
            on = value;
        } while (value != (selfTesting || SESSION.get() != null));
    }

    // ---- the agent's side
    // ----------------------------------------------------------------------------------------------

    /** Called by the agent once its transformer passed, or lost, its self-test; a removal ends any session. */
    public static void installed(boolean value, String reason) {
        if (!value) {
            installed = false;
            unavailableReason = reason == null ? "the dynamic-access sensor is not installed" : reason;
            endAll("sensor removed");
        } else {
            unavailableReason = null;
            installed = true;
            startPending();
        }
    }

    /** Begins the agent's self-test on the calling thread: hits of that thread only are counted, nothing recorded. */
    public static void beginSelfTest() {
        for (int i = 0; i < HOOKS.length; i++) {
            SELF_TEST_HITS.set(i, 0L);
        }
        Reentrancy.dynamicSelfTest(true);
        selfTesting = true;
        refresh();
    }

    /** Ends the self-test and returns each hook's hits on the self-test thread. */
    public static Map<String, Object> endSelfTest() {
        Reentrancy.dynamicSelfTest(false);
        selfTesting = false;
        refresh();
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < HOOKS.length; i++) {
            map.put(HOOKS[i], Long.valueOf(SELF_TEST_HITS.get(i)));
        }
        return map;
    }

    /** Every hook's id, in index order. */
    public static List<String> hooks() {
        List<String> list = new ArrayList<String>();
        for (int i = 0; i < HOOKS.length; i++) {
            list.add(HOOKS[i]);
        }
        return list;
    }

    // ---- helpers
    // --------------------------------------------------------------------------------------------------------

    static void error(Throwable ex) {
        ERRORS.increment();
        AgentBridge.error(ex);
    }

    private static Map<String, Object> answer(String status, String reason) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("status", status);
        map.put("reason", reason);
        return map;
    }

    private static int bounded(Object value, int fallback, int max) {
        if (!(value instanceof Number)) {
            return fallback;
        }
        long number = ((Number) value).longValue();
        return (int) Math.max(1L, Math.min(max, number));
    }

    private static String[] packages(List<String> packages) {
        String[] prefixes = new String[packages.size()];
        for (int i = 0; i < prefixes.length; i++) {
            prefixes[i] = packages.get(i) + ".";
        }
        return prefixes;
    }

    static boolean jdk(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        return loader == null || loader == PLATFORM;
    }

    static boolean tooling(String name) {
        for (String prefix : TOOLING) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Loads and initializes the walker on the starting thread, never first inside an application's advised call. */
    private static void warm(Session session) {
        if (Reentrancy.enterDynamic()) {
            try {
                Walker.INSTANCE.walk(new Caller(FOR_NAME, session.packages, false));
            } finally {
                Reentrancy.exitDynamic();
            }
        }
    }

    /** Tests only: ends any session and forgets the last one. */
    static void reset() {
        SESSION.set(null);
        LAST.set(null);
        STARTUP.set(null);
        selfTesting = false;
        installed = false;
        unavailableReason = "the dynamic-access sensor is not installed";
        on = false;
        ERRORS.reset();
    }

    /** One recording session: its bounds, its entries, and why calls were left out. */
    static final class Session {

        final long token;
        final int seconds;
        final int maxEntries;
        final String[] packages;
        final long startedMillis = System.currentTimeMillis();
        final long deadline;
        final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<String, Entry>();
        final AtomicInteger size = new AtomicInteger();
        final AtomicLongArray hits = new AtomicLongArray(HOOKS.length);
        final LongAdder recorded = new LongAdder();
        final LongAdder dropped = new LongAdder();
        final LongAdder jdkCallers = new LongAdder();
        final LongAdder toolingCallers = new LongAdder();
        final LongAdder generatedCallers = new LongAdder();
        final LongAdder unknownCallers = new LongAdder();
        final LongAdder bootUiWork = new LongAdder();
        final LongAdder failedLookups = new LongAdder();
        final LongAdder oversized = new LongAdder();
        final LongAdder walkNanos = new LongAdder();
        volatile boolean closed;
        volatile String endReason;
        volatile long endedMillis;

        Session(long token, int seconds, int maxEntries, String[] packages) {
            this.token = token;
            this.seconds = seconds;
            this.maxEntries = maxEntries;
            this.packages = packages;
            this.deadline = System.nanoTime() + seconds * 1_000_000_000L;
        }

        void record(int kind, Object subject) {
            if (closed) {
                // A thread that read the session just before it ended: the ended session stays as it was.
                return;
            }
            Claim claim = AgentBridge.current();
            if (claim == null || !claim.armed || claim.token != token) {
                end(this, "run ended");
                return;
            }
            long started = System.nanoTime();
            // The immediate caller only: a repeated access stops there, and only a new entry walks on.
            Caller caller = Walker.INSTANCE.walk(new Caller(kind, packages, false));
            walkNanos.add(System.nanoTime() - started);
            if (caller.verdict == Caller.JDK) {
                jdkCallers.increment();
                return;
            }
            if (caller.verdict == Caller.TOOLING) {
                toolingCallers.increment();
                return;
            }
            if (caller.verdict == Caller.GENERATED) {
                generatedCallers.increment();
                return;
            }
            if (caller.callerClass == null) {
                unknownCallers.increment();
                return;
            }
            String type;
            String member = null;
            String parameters = null;
            if (kind == FOR_NAME) {
                type = ((Class<?>) subject).getTypeName();
            } else if (kind == INVOKE) {
                Method method = (Method) subject;
                type = method.getDeclaringClass().getTypeName();
                member = method.getName();
                parameters = names(method.getParameterTypes());
            } else if (kind == NEW_INSTANCE) {
                Constructor<?> constructor = (Constructor<?>) subject;
                type = constructor.getDeclaringClass().getTypeName();
                member = "<init>";
                parameters = names(constructor.getParameterTypes());
            } else {
                type = names((Class<?>[]) subject);
            }
            if (type == null || type.length() > MAX_NAME || (parameters != null && parameters.length() > MAX_NAME)) {
                oversized.increment();
                return;
            }
            StringBuilder key = new StringBuilder(type.length() + 64)
                    .append(kind)
                    .append('\n')
                    .append(type)
                    .append('\n')
                    .append(member)
                    .append('\n')
                    .append(parameters)
                    .append('\n')
                    .append(caller.callerClass);
            String text = key.toString();
            Entry entry = entries.get(text);
            if (entry == null) {
                if (size.incrementAndGet() > maxEntries) {
                    size.decrementAndGet();
                    dropped.increment();
                    return;
                }
                String application = caller.application;
                if (application == null) {
                    long deep = System.nanoTime();
                    application = Walker.INSTANCE.walk(new Caller(kind, packages, true)).application;
                    walkNanos.add(System.nanoTime() - deep);
                }
                Entry created =
                        new Entry(kind, type, member, parameters, caller.callerClass, caller.callerMethod, application);
                entry = entries.putIfAbsent(text, created);
                if (entry == null) {
                    entry = created;
                } else {
                    size.decrementAndGet();
                }
            }
            entry.count.increment();
            recorded.increment();
        }

        Map<String, Object> result(boolean withEntries) {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("startedMillis", Long.valueOf(startedMillis));
            map.put("seconds", Integer.valueOf(seconds));
            map.put("maxEntries", Integer.valueOf(maxEntries));
            map.put("endReason", endReason);
            map.put("endedMillis", endReason == null ? null : Long.valueOf(endedMillis));
            map.put("distinct", Integer.valueOf(size.get()));
            map.put("recorded", Long.valueOf(recorded.sum()));
            map.put("dropped", Long.valueOf(dropped.sum()));
            map.put("jdkCallers", Long.valueOf(jdkCallers.sum()));
            map.put("toolingCallers", Long.valueOf(toolingCallers.sum()));
            map.put("generatedCallers", Long.valueOf(generatedCallers.sum()));
            map.put("unknownCallers", Long.valueOf(unknownCallers.sum()));
            map.put("bootUiWork", Long.valueOf(bootUiWork.sum()));
            map.put("failedLookups", Long.valueOf(failedLookups.sum()));
            map.put("oversized", Long.valueOf(oversized.sum()));
            map.put("walkNanos", Long.valueOf(walkNanos.sum()));
            Map<String, Object> hookHits = new LinkedHashMap<String, Object>();
            for (int i = 0; i < HOOKS.length; i++) {
                hookHits.put(HOOKS[i], Long.valueOf(hits.get(i)));
            }
            map.put("hooks", hookHits);
            if (withEntries) {
                List<Object> rows = new ArrayList<Object>();
                Iterator<Entry> iterator = entries.values().iterator();
                while (iterator.hasNext()) {
                    rows.add(iterator.next().describe());
                }
                map.put("entries", rows);
            }
            return map;
        }

        private static String names(Class<?>[] types) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < types.length; i++) {
                if (i > 0) {
                    text.append(',');
                }
                text.append(types[i].getTypeName());
                if (text.length() > MAX_NAME) {
                    return text.toString();
                }
            }
            return text.toString();
        }
    }

    /** One distinct access: what was accessed, from which caller class; the first caller method and app frame. */
    static final class Entry {

        final int kind;
        final String type;
        final String member;
        final String parameters;
        final String callerClass;
        final String callerMethod;
        final String applicationFrame;
        final LongAdder count = new LongAdder();

        Entry(
                int kind,
                String type,
                String member,
                String parameters,
                String callerClass,
                String callerMethod,
                String applicationFrame) {
            this.kind = kind;
            this.type = type;
            this.member = member;
            this.parameters = parameters;
            this.callerClass = callerClass;
            this.callerMethod = callerMethod;
            this.applicationFrame = applicationFrame;
        }

        Map<String, Object> describe() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("kind", KINDS[kind]);
            map.put("type", type);
            map.put("member", member);
            map.put("parameterTypes", parameters);
            map.put("callerClass", callerClass);
            map.put("callerMethod", callerMethod);
            map.put("applicationFrame", applicationFrame);
            map.put("count", Long.valueOf(count.sum()));
            return map;
        }
    }

    /** A startup session a claim asked for. */
    static final class Startup {

        final long token;
        final Map<String, Object> options;

        Startup(long token, Map<String, Object> options) {
            this.token = token;
            this.options = options;
        }
    }

    /** The stack walker, created on first use by a session rather than when the bridge loads. */
    static final class Walker {

        static final StackWalker INSTANCE = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

        private Walker() {}
    }

    /**
     * One bounded walk: skips the bridge's frames and the advised method's own frames when the walker shows them (it
     * hides {@code Method.invoke} and {@code Constructor.newInstance}; a public {@code Class.forName} and the
     * caller-sensitive adapter it calls on JDK 18+ are both shown), and takes the next frame as the immediate caller.
     * A deep walk then looks for the first frame of a claimed package at or above it, within {@value #FRAMES} frames.
     */
    static final class Caller implements Function<Stream<StackWalker.StackFrame>, Caller> {

        static final int FOUND = 0;
        static final int JDK = 1;
        static final int TOOLING = 2;
        static final int GENERATED = 3;

        private final int kind;
        private final String[] packages;
        private final boolean deep;
        int verdict = FOUND;
        String callerClass;
        String callerMethod;
        String application;

        Caller(int kind, String[] packages, boolean deep) {
            this.kind = kind;
            this.packages = packages;
            this.deep = deep;
        }

        @Override
        public Caller apply(Stream<StackWalker.StackFrame> stream) {
            Iterator<StackWalker.StackFrame> frames = stream.iterator();
            boolean pastAdvised = false;
            int seen = 0;
            while (frames.hasNext() && seen < FRAMES) {
                StackWalker.StackFrame frame = frames.next();
                seen++;
                Class<?> type = frame.getDeclaringClass();
                String name = type.getName();
                if (callerClass == null) {
                    if (name.startsWith(BRIDGE)) {
                        continue;
                    }
                    // Every frame of the advised method: a public Class.forName calls its caller-sensitive adapter.
                    if (!pastAdvised
                            && name.equals(OWNERS[kind])
                            && frame.getMethodName().equals(METHODS[kind])) {
                        continue;
                    }
                    pastAdvised = true;
                    if (jdk(type)) {
                        verdict = JDK;
                        return this;
                    }
                    if (tooling(name)) {
                        verdict = TOOLING;
                        return this;
                    }
                    if (Exclusions.generated(name)) {
                        verdict = GENERATED;
                        return this;
                    }
                    callerClass = name;
                    callerMethod = frame.getMethodName();
                    if (claimed(name)) {
                        application = name + "#" + callerMethod;
                        return this;
                    }
                    if (!deep) {
                        return this;
                    }
                } else if (claimed(name)) {
                    application = name + "#" + frame.getMethodName();
                    return this;
                }
            }
            return this;
        }

        private boolean claimed(String name) {
            for (String prefix : packages) {
                if (name.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }
}
