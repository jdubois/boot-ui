package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.DynamicAccess;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

/**
 * The experimental {@code dynamic-access} sensor (PLAN-v2 M5-9b spike, §5.15): inline advice on {@code Class.forName},
 * {@code Method.invoke}, {@code Constructor.newInstance}, and {@code Proxy.newProxyInstance}, feeding the bridge's
 * {@link DynamicAccess} recording sessions. Off unless the JVM sets {@value #FLAG}, and installed only for a claim asking
 * for the sensor; {@value #EXTENDED_FLAG} adds count-only hooks on the other dynamic accesses §5.15 names, as the spike's
 * retransformation evidence. A behavioral self-test exercises every hook from the agent's own class loader, so a
 * caller-sensitive {@code Class.forName(String)} only finds its probe class if the advice kept the caller frame; a
 * failure removes the transformer and leaves the sensor unavailable.
 */
final class DynamicAccessSensor {

    static final String FLAG = "bootui.agent.experimental.dynamic-access";
    static final String EXTENDED_FLAG = FLAG + ".extended";

    static final String CLASS = "java.lang.Class";
    static final String METHOD = "java.lang.reflect.Method";
    static final String CONSTRUCTOR = "java.lang.reflect.Constructor";
    static final String PROXY = "java.lang.reflect.Proxy";
    static final String FIELD = "java.lang.reflect.Field";
    static final String CLASS_LOADER = "java.lang.ClassLoader";
    static final String OBJECT_INPUT_STREAM = "java.io.ObjectInputStream";
    static final String OBJECT_STREAM_CLASS = "java.io.ObjectStreamClass";

    /** The hook types, by index of {@link DynamicAccess#hooks()}. */
    static final String[] HOOK_TYPES = {
        CLASS,
        CLASS,
        CLASS,
        METHOD,
        CONSTRUCTOR,
        PROXY,
        CLASS,
        CLASS,
        CLASS,
        FIELD,
        CLASS,
        CLASS_LOADER,
        OBJECT_INPUT_STREAM,
        OBJECT_STREAM_CLASS,
        PROXY
    };

    /** The hooks a session records: each must pass the self-test. */
    static final int RECORDED_HOOKS = DynamicAccess.HOOK_NEW_PROXY_INSTANCE + 1;

    private static final int INSTALL = 1;
    private static final int RELEASE = 2;

    private final Instrumentation instrumentation;
    private final boolean privileged;
    private final boolean enabled;
    private final boolean extended;
    private final TransformStats stats = new TransformStats();

    private volatile ResettableClassFileTransformer transformer;
    private volatile String state;
    private volatile long installMillis = -1;
    private volatile long selfTestMillis = -1;
    private volatile Map<String, Object> selfTest = new LinkedHashMap<String, Object>();
    private volatile boolean selfTestPassed;
    private volatile String selfTestError;
    private volatile boolean stuck;
    private Thread worker;
    private int pending;

    DynamicAccessSensor(Instrumentation instrumentation, boolean privileged) {
        this(instrumentation, privileged, Boolean.getBoolean(FLAG), Boolean.getBoolean(EXTENDED_FLAG));
    }

    DynamicAccessSensor(Instrumentation instrumentation, boolean privileged, boolean enabled, boolean extended) {
        this.instrumentation = instrumentation;
        this.privileged = privileged;
        this.enabled = enabled;
        this.extended = extended;
        this.state = enabled ? "off" : "disabled";
    }

    /** Installs the sensor once and self-tests it, off the claiming thread; nothing unless the JVM flag is set. */
    synchronized void claimed() {
        if (!enabled) {
            DynamicAccess.installed(
                    false, "the dynamic-access sensor is experimental: start the JVM with -D" + FLAG + "=true");
            return;
        }
        if (stuck) {
            return;
        }
        if (transformer != null && selfTestPassed && pending != RELEASE) {
            pending = 0;
            return;
        }
        schedule(INSTALL);
    }

    /** Ends any session and restores every advised class, off the caller's thread. */
    synchronized void release() {
        DynamicAccess.installed(false, "the dynamic-access sensor was released");
        if (transformer != null || pending != 0 || worker != null) {
            schedule(RELEASE);
        }
    }

    private void schedule(int job) {
        pending = job;
        if (worker == null) {
            worker = AgentThreads.newThread("bootui-agent-dynamic-access", new Worker(), privileged);
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

    final class Worker implements Runnable {

        @Override
        public void run() {
            int job;
            while ((job = nextJob()) != 0) {
                try {
                    if (job == RELEASE) {
                        reset();
                    } else if (!stuck) {
                        if (transformer == null) {
                            install();
                        }
                        selfTest();
                    }
                } catch (Throwable ex) {
                    selfTestPassed = false;
                    selfTestError = "dynamic-access sensor error: " + ex;
                    DynamicAccess.installed(false, selfTestError);
                    state = "failed";
                    stats.failure("dynamic-access: " + ex);
                    AgentBridge.message("the BootUI agent could not install its dynamic-access sensor: " + ex);
                }
            }
        }
    }

    void install() {
        long started = System.nanoTime();
        state = "installing";
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

    void reset() {
        ResettableClassFileTransformer installed = transformer;
        transformer = null;
        selfTestPassed = false;
        DynamicAccess.installed(false, "the dynamic-access sensor was released");
        if (installed == null) {
            state = stuck ? "release-failed" : "released";
            return;
        }
        long started = System.nanoTime();
        int skippedBefore = stats.skippedCount();
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
        // Byte Buddy's answer covers the transformer's removal; a class it could not restore is only reported.
        restored &= stats.skippedCount() == skippedBefore;
        if (!restored) {
            // The advice stays, reading a flag no session can set again: off for good, at one volatile read.
            stuck = true;
        }
        state = restored ? "released" : "release-failed";
    }

    final class InstallAction implements PrivilegedAction<ResettableClassFileTransformer> {

        @Override
        public ResettableClassFileTransformer run() {
            return builder().installOn(instrumentation);
        }
    }

    private AgentBuilder builder() {
        List<String> types = new ArrayList<String>(List.of(CLASS, METHOD, CONSTRUCTOR, PROXY));
        if (extended) {
            types.addAll(List.of(FIELD, CLASS_LOADER, OBJECT_INPUT_STREAM, OBJECT_STREAM_CLASS));
        }
        ExecutorSensor.Visit classHooks = new ExecutorSensor.Visit(java.util.Collections.<String>emptySet());
        ExecutorSensor.Visit proxyHooks = new ExecutorSensor.Visit(java.util.Collections.<String>emptySet())
                .and(
                        null,
                        hook(DynamicAccessAdvice.NewProxyInstance.class, DynamicAccess.HOOK_NEW_PROXY_INSTANCE)
                                .on(publicNamed("newProxyInstance")
                                        .and(ElementMatchers.takesArguments(
                                                ClassLoader.class, Class[].class, InvocationHandler.class))));
        if (extended) {
            classHooks = classHooks
                    .and(
                            null,
                            counted(DynamicAccess.HOOK_METHODS)
                                    .on(publicNamed(
                                            "getMethod", "getMethods", "getDeclaredMethod", "getDeclaredMethods")))
                    .and(
                            null,
                            counted(DynamicAccess.HOOK_CONSTRUCTORS)
                                    .on(publicNamed(
                                            "getConstructor",
                                            "getConstructors",
                                            "getDeclaredConstructor",
                                            "getDeclaredConstructors")))
                    .and(
                            null,
                            counted(DynamicAccess.HOOK_FIELDS)
                                    .on(publicNamed("getField", "getFields", "getDeclaredField", "getDeclaredFields")))
                    .and(
                            null,
                            counted(DynamicAccess.HOOK_CLASS_RESOURCE)
                                    .on(publicNamed("getResource", "getResourceAsStream")));
            proxyHooks =
                    proxyHooks.and(null, counted(DynamicAccess.HOOK_PROXY_CLASS).on(publicNamed("getProxyClass")));
        }
        AgentBuilder.Identified.Extendable builder = stats.configure(new AgentBuilder.Default())
                .assureReadEdgeTo(instrumentation, DynamicAccess.class)
                .ignore(ElementMatchers.not(ElementMatchers.<TypeDescription>namedOneOf(types.toArray(new String[0]))))
                .type(ElementMatchers.named(CLASS))
                .transform(new ForNameVisit())
                .transform(classHooks)
                .type(ElementMatchers.named(METHOD))
                .transform(new ExecutorSensor.Visit(java.util.Collections.<String>emptySet())
                        .and(
                                null,
                                hook(DynamicAccessAdvice.Invoke.class, DynamicAccess.HOOK_INVOKE)
                                        .on(publicNamed("invoke")
                                                .and(ElementMatchers.takesArguments(Object.class, Object[].class)))))
                .type(ElementMatchers.named(CONSTRUCTOR))
                .transform(new ExecutorSensor.Visit(java.util.Collections.<String>emptySet())
                        .and(
                                null,
                                hook(DynamicAccessAdvice.NewInstance.class, DynamicAccess.HOOK_NEW_INSTANCE)
                                        .on(publicNamed("newInstance")
                                                .and(ElementMatchers.takesArguments(Object[].class)))))
                .type(ElementMatchers.named(PROXY))
                .transform(proxyHooks);
        if (extended) {
            builder = builder.type(ElementMatchers.named(FIELD))
                    .transform(new ExecutorSensor.Visit(java.util.Collections.<String>emptySet())
                            .and(null, counted(DynamicAccess.HOOK_FIELD_VALUE).on(publicNamed("get", "set"))))
                    .type(ElementMatchers.named(CLASS_LOADER))
                    .transform(new ExecutorSensor.Visit(java.util.Collections.<String>emptySet())
                            .and(
                                    null,
                                    counted(DynamicAccess.HOOK_LOADER_RESOURCE)
                                            .on(publicNamed("getResource", "getResources", "getResourceAsStream")
                                                    .and(ElementMatchers.not(ElementMatchers.isStatic())))))
                    .type(ElementMatchers.named(OBJECT_INPUT_STREAM))
                    .transform(new ExecutorSensor.Visit(java.util.Collections.<String>emptySet())
                            .and(
                                    null,
                                    counted(DynamicAccess.HOOK_RESOLVE_CLASS)
                                            .on(ElementMatchers.named("resolveClass"))))
                    .type(ElementMatchers.named(OBJECT_STREAM_CLASS))
                    .transform(new ExecutorSensor.Visit(java.util.Collections.<String>emptySet())
                            .and(
                                    null,
                                    counted(DynamicAccess.HOOK_DESERIALIZED)
                                            .on(ElementMatchers.named("initNonProxy"))));
        }
        return builder;
    }

    /**
     * The three {@code Class.forName} hooks. On JDK 18+, each public overload that has a private
     * {@code @CallerSensitiveAdapter} (the same parameters and the caller class) delegates to it, and reflection calls
     * the adapter directly: the adapter is advised then, so a reflective {@code Class.forName} still records the class
     * it loads, and a direct one records once. Without an adapter (JDK 17, and the overloads JDK 26 no longer makes
     * caller-sensitive), the public overload is.
     */
    static final class ForNameVisit implements AgentBuilder.Transformer {

        @Override
        public DynamicType.Builder<?> transform(
                DynamicType.Builder<?> builder,
                TypeDescription type,
                ClassLoader classLoader,
                JavaModule module,
                ProtectionDomain protectionDomain) {
            DynamicType.Builder<?> result = forName(builder, type, DynamicAccess.HOOK_FOR_NAME, String.class);
            result = forName(
                    result, type, DynamicAccess.HOOK_FOR_NAME_LOADER, String.class, boolean.class, ClassLoader.class);
            return forName(result, type, DynamicAccess.HOOK_FOR_NAME_MODULE, Module.class, String.class);
        }

        private static DynamicType.Builder<?> forName(
                DynamicType.Builder<?> builder, TypeDescription type, int hook, Class<?>... parameters) {
            Class<?>[] adapterParameters = Arrays.copyOf(parameters, parameters.length + 1);
            adapterParameters[parameters.length] = Class.class;
            ElementMatcher.Junction<MethodDescription> adapter = ElementMatchers.named("forName")
                    .and(ElementMatchers.isStatic())
                    .and(ElementMatchers.isPrivate())
                    .and(ElementMatchers.takesArguments(adapterParameters));
            boolean hasAdapter = !type.getDeclaredMethods().filter(adapter).isEmpty();
            ElementMatcher.Junction<MethodDescription> target = hasAdapter
                    ? adapter
                    : publicNamed("forName")
                            .and(ElementMatchers.isStatic())
                            .and(ElementMatchers.takesArguments(parameters));
            return builder.visit(hook(DynamicAccessAdvice.ForName.class, hook).on(target));
        }
    }

    private static ElementMatcher.Junction<MethodDescription> publicNamed(String... names) {
        return ElementMatchers.isPublic().and(ElementMatchers.namedOneOf(names));
    }

    private static Advice hook(Class<?> advice, int hook) {
        return Advice.withCustomMapping()
                .bind(DynamicAccessAdvice.Hook.class, Integer.valueOf(hook))
                .to(advice);
    }

    private static Advice counted(int hook) {
        return hook(DynamicAccessAdvice.Counted.class, hook);
    }

    // ---- self-test -----------------------------------------------------------------------------------------------

    void selfTest() {
        selfTestPassed = false;
        selfTestError = null;
        state = "testing";
        long started = System.nanoTime();
        Map<String, Object> hits;
        String outcome;
        DynamicAccess.beginSelfTest();
        try {
            outcome = ExecutorSensor.step(new SelfTestStep(extended), 5);
        } finally {
            hits = DynamicAccess.endSelfTest();
        }
        selfTestMillis = (System.nanoTime() - started) / 1_000_000L;
        selfTest = hits;
        List<String> hooks = DynamicAccess.hooks();
        List<String> failed = new ArrayList<String>();
        for (int i = 0; i < RECORDED_HOOKS; i++) {
            Object count = hits.get(hooks.get(i));
            if (!(count instanceof Long) || (Long) count == 0L) {
                failed.add(hooks.get(i));
            }
        }
        if ("ok".equals(outcome) && failed.isEmpty()) {
            selfTestPassed = true;
            state = "installed";
            DynamicAccess.installed(true, null);
        } else {
            selfTestError = "self-test " + outcome + ", hooks not exercised: " + failed;
            AgentBridge.message(
                    "the BootUI agent's dynamic-access sensor failed its self-test and was removed: " + selfTestError);
            reset();
            DynamicAccess.installed(false, selfTestError);
            state = stuck ? "self-test-failed (release-failed)" : "self-test-failed";
        }
    }

    Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("id", DynamicAccess.SENSOR);
        map.put("state", state);
        map.put("enabled", Boolean.valueOf(enabled));
        map.put("extended", Boolean.valueOf(extended));
        map.put("durationMillis", Long.valueOf(ExecutorSensor.durationMillis(installMillis, selfTestMillis)));
        map.put("installMillis", Long.valueOf(installMillis));
        map.put("selfTestMillis", Long.valueOf(selfTestMillis));
        map.put("selfTestPassed", Boolean.valueOf(selfTestPassed));
        map.put("selfTestError", selfTestError);
        List<Object> hooks = new ArrayList<Object>();
        List<String> ids = DynamicAccess.hooks();
        Map<String, Object> results = selfTest;
        for (int i = 0; i < ids.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", ids.get(i));
            row.put("kind", i < RECORDED_HOOKS ? "record" : "count");
            row.put("type", HOOK_TYPES[i]);
            row.put("transformed", Boolean.valueOf(stats.transformed(HOOK_TYPES[i])));
            Object hits = results.get(ids.get(i));
            row.put("selfTest", hits == null ? "not-run" : hits);
            hooks.add(row);
        }
        map.put("hooks", hooks);
        stats.putInto(map);
        return map;
    }

    /**
     * Every hook once, from the agent's class loader: {@code Class.forName(String)} must find {@link Probe}, which only
     * that loader defines, so it fails if the advice changed the caller frame the method resolves its loader from.
     */
    static final class SelfTestStep implements ExecutorSensor.Step {

        private final boolean extended;

        SelfTestStep(boolean extended) {
            this.extended = extended;
        }

        @Override
        @SuppressWarnings({"deprecation", "removal"})
        public void run(int seconds) throws Exception {
            ClassLoader loader = Probe.class.getClassLoader();
            if (Class.forName(Probe.class.getName()) != Probe.class) {
                throw new IllegalStateException("Class.forName resolved through another class loader");
            }
            // Through reflection: JDK 17's security stack walk must still skip the advised Method.invoke, and JDK 18+
            // calls the advised caller-sensitive adapter with the caller passed explicitly.
            if (Class.class.getMethod("forName", String.class).invoke(null, Probe.class.getName()) != Probe.class) {
                throw new IllegalStateException("a reflective Class.forName resolved through another class loader");
            }
            Class.forName(Probe.class.getName(), false, loader);
            Class.forName(Object.class.getModule(), "java.lang.String");
            Object probe = Probe.class.getDeclaredConstructor().newInstance();
            Method run = Probe.class.getDeclaredMethod("run");
            run.invoke(probe);
            Runnable proxy = (Runnable) Proxy.newProxyInstance(loader, new Class<?>[] {Runnable.class}, new Handler());
            proxy.run();
            if (extended) {
                Probe.class.getDeclaredFields();
                Probe.class.getDeclaredField("value").get(probe);
                Probe.class.getResource("Probe.class");
                ClassLoader.getSystemClassLoader().getResource("bootui-agent-self-test");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                    out.writeObject(Integer.valueOf(42));
                }
                try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                    in.readObject();
                }
                Proxy.getProxyClass(loader, Runnable.class);
            }
        }
    }

    /** The self-test's reflective target, defined only by the agent's class loader. */
    public static final class Probe implements Runnable {

        public int value = 1;

        public Probe() {}

        @Override
        public void run() {
            value++;
        }
    }

    static final class Handler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return null;
        }
    }
}
