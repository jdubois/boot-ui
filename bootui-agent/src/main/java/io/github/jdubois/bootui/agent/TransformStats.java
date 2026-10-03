package io.github.jdubois.bootui.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
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
                .with(new Transformations());
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
