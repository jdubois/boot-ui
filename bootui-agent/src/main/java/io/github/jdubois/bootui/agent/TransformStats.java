package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.SideEffects;
import java.lang.instrument.ClassFileTransformer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.StubMethod;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;

/**
 * One transformer's Byte Buddy configuration and counters (PLAN-v2 D32): retransformation in batches of 64 split down to
 * one class on failure, {@code DECORATE}, a listener naming every class still failing (Byte Buddy swallows them by
 * default), and a type pool falling back to bootstrap-loaded types only, for interfaces other agents append at runtime.
 */
final class TransformStats {

    private static final int FAILURES = 20;

    private final AtomicInteger transformed = new AtomicInteger();
    private final AtomicInteger retransformed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger fallbacks = new AtomicInteger();
    private final AtomicLong retransformNanos = new AtomicLong();
    private final List<String> failures = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> fallbackTypes = Collections.synchronizedList(new ArrayList<String>());
    private final Set<String> transformedTypes = ConcurrentHashMap.newKeySet();

    /** {@code builder} with the retransformation rules, the failure listeners, and the fallback type pool. */
    AgentBuilder configure(AgentBuilder builder) {
        return configure(builder, null);
    }

    /** {@link #configure(AgentBuilder)}, also telling {@code redefinitions} of every retransformation batch's outcome. */
    AgentBuilder configure(AgentBuilder builder, AgentBuilder.RedefinitionStrategy.Listener redefinitions) {
        warmTransformation();
        return configureOnly(builder, redefinitions);
    }

    private AgentBuilder configureOnly(AgentBuilder builder, AgentBuilder.RedefinitionStrategy.Listener redefinitions) {
        AgentBuilder.RedefinitionListenable listenable = builder.disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.RedefinitionStrategy.BatchAllocator.ForFixedSize.ofSize(64))
                .with(AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Reiterating.INSTANCE)
                .with(AgentBuilder.RedefinitionStrategy.Listener.BatchReallocator.splitting())
                .with(new RedefinitionFailures());
        if (redefinitions != null) {
            listenable = listenable.with(redefinitions);
        }
        return listenable
                .with(AgentBuilder.TypeStrategy.Default.DECORATE)
                .with(new BootstrapFallbackPoolStrategy())
                .with(new Transformations())
                .with(new AgentWork());
    }

    private static boolean transformationWarmed;

    /** Whether the warm-up's advice was applied, which {@link #warmTransformation()} never reports otherwise. */
    private static volatile boolean transformationWarmedUp;

    static boolean transformationWarmedUp() {
        return transformationWarmedUp;
    }

    /**
     * Defines, once, the Byte Buddy classes a transformation loads on first use, before any BootUI transformer is
     * registered. They are defined by the agent's class loader, which calls every registered transformer for each class
     * it defines; first used from inside a transformer, as for a JDK class another agent's transformer was the first to
     * load, a thread holding such a class's loading lock can wait for a JDK class that a second thread, running this
     * transformer on that very class, is loading and waits on the same lock: a deadlock beside the OpenTelemetry agent.
     * Every Byte Buddy class is defined, and a throwaway transformer applies advice to a generated class, so none of
     * that is left for the first real class. Never fails an install.
     */
    static synchronized void warmTransformation() {
        if (transformationWarmed) {
            return;
        }
        transformationWarmed = true;
        try {
            if (TransformStats.class.getClassLoader() instanceof AgentClassLoader) {
                // Every Byte Buddy class, not only the ones the dry run below reaches: advice binding, the type pool's
                // lazy types, and a visitor's own helpers differ by what a sensor transforms.
                ((AgentClassLoader) TransformStats.class.getClassLoader())
                        .define("io.github.jdubois.bootui.agent.shaded.bytebuddy.");
            }
            byte[] bytes = new ByteBuddy()
                    .subclass(Object.class)
                    .name("bootui.warm.Up")
                    .defineMethod("up", void.class, Visibility.PUBLIC)
                    .intercept(StubMethod.INSTANCE)
                    .make()
                    .getBytes();
            ClassFileTransformer warm = new TransformStats()
                    .configureOnly(new AgentBuilder.Default(), null)
                    .ignore(ElementMatchers.none())
                    .type(ElementMatchers.named("bootui.warm.Up"))
                    .transform((builder, type, classLoader, module, domain) ->
                            builder.visit(Advice.to(WarmAdvice.class).on(ElementMatchers.named("up"))))
                    .makeRaw();
            transformationWarmedUp =
                    warm.transform(TransformStats.class.getClassLoader(), "bootui/warm/Up", null, null, bytes) != null;
        } catch (Throwable ignored) {
            // Only a warm-up: the first real class pays for what it did not do.
        }
    }

    AgentBuilder.RedefinitionStrategy.Listener redefinitionFailures() {
        return new RedefinitionFailures();
    }

    void skipped(String type, Throwable error) {
        skipped.incrementAndGet();
        failure(type + ": " + error);
    }

    void failure(String text) {
        synchronized (failures) {
            if (failures.size() < FAILURES) {
                failures.add(text);
            }
        }
    }

    /** Adds a measured interval spent retransforming: installing, refining, or restoring the transformer. */
    void retransformedFor(long nanos) {
        retransformNanos.addAndGet(Math.max(0L, nanos));
    }

    boolean transformed(String type) {
        return transformedTypes.contains(type);
    }

    boolean transformedAnyStartingWith(String prefix) {
        for (String type : transformedTypes) {
            if (type.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    void putInto(Map<String, Object> map) {
        map.put("transformed", Integer.valueOf(transformed.get()));
        map.put("retransformed", Integer.valueOf(retransformed.get()));
        map.put("failed", Integer.valueOf(failed.get()));
        map.put("skipped", Integer.valueOf(skipped.get()));
        map.put("poolFallbacks", Integer.valueOf(fallbacks.get()));
        map.put("retransformMillis", Long.valueOf(retransformNanos.get() / 1_000_000L));
        synchronized (failures) {
            map.put("failures", new ArrayList<String>(failures));
        }
        synchronized (fallbackTypes) {
            map.put("fallbackTypes", new ArrayList<String>(fallbackTypes));
        }
    }

    final class Transformations extends AgentBuilder.Listener.Adapter {

        @Override
        public void onTransformation(
                TypeDescription type,
                ClassLoader classLoader,
                JavaModule module,
                boolean loaded,
                DynamicType dynamicType) {
            transformed.incrementAndGet();
            transformedTypes.add(type.getName());
            if (loaded) {
                retransformed.incrementAndGet();
            }
        }

        @Override
        public void onError(
                String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable error) {
            failed.incrementAndGet();
            failure(typeName + ": " + error);
        }
    }

    /**
     * Marks the thread's work as the agent's own while the transformer handles a class, from its discovery to its
     * completion, which Byte Buddy reports in a {@code finally}: what Byte Buddy does meanwhile, such as reading class
     * files, is never recorded by the side-effect sensors (PLAN-v2 M5-5 design B3).
     */
    static final class AgentWork extends AgentBuilder.Listener.Adapter {

        @Override
        public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
            SideEffects.agentWork(true);
        }

        @Override
        public void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
            SideEffects.agentWork(false);
        }
    }

    final class RedefinitionFailures extends AgentBuilder.RedefinitionStrategy.Listener.Adapter {

        @Override
        public Iterable<? extends List<Class<?>>> onError(
                int index, List<Class<?>> batch, Throwable throwable, List<Class<?>> types) {
            if (batch.size() == 1) {
                skipped(batch.get(0).getName(), throwable);
            }
            return Collections.emptyList();
        }
    }

    /** The advice of {@link #warmTransformation()}'s generated class: does nothing. */
    static final class WarmAdvice {

        private WarmAdvice() {}

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter() {}
    }

    final class BootstrapFallbackPoolStrategy implements AgentBuilder.PoolStrategy {

        @Override
        public TypePool typePool(ClassFileLocator locator, ClassLoader classLoader) {
            return new BootstrapFallbackPool(locator);
        }

        @Override
        public TypePool typePool(ClassFileLocator locator, ClassLoader classLoader, String name) {
            return new BootstrapFallbackPool(locator);
        }
    }

    final class BootstrapFallbackPool extends TypePool.Default {

        BootstrapFallbackPool(ClassFileLocator locator) {
            super(new TypePool.CacheProvider.Simple(), locator, TypePool.Default.ReaderMode.FAST);
        }

        @Override
        protected Resolution doDescribe(String name) {
            Resolution resolution = super.doDescribe(name);
            if (resolution.isResolved()) {
                return resolution;
            }
            try {
                Class<?> type = Class.forName(name, false, null);
                if (fallbacks.incrementAndGet() <= FAILURES) {
                    fallbackTypes.add(name);
                }
                return new Resolution.Simple(TypeDescription.ForLoadedType.of(type));
            } catch (Throwable ex) {
                return resolution;
            }
        }
    }
}
