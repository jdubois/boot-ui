package bootuiagentit;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** A real old object and its changed replacement, retained across a claim in separate reloadable loaders. */
public final class InventoryReload {

    private InventoryReload() {}

    public static void main(String[] args) throws Exception {
        InventoryBehaviors.bridge = Class.forName(InventoryBehaviors.BRIDGE + "AgentBridge", true, null);
        InventoryBehaviors.inventory = Class.forName(InventoryBehaviors.BRIDGE + "CodeInventory", true, null);
        InventoryBehaviors.ring = Class.forName(InventoryBehaviors.BRIDGE + "AgentRing", true, null);
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        boolean lazy = "unseen-lazy".equals(args[2]);
        String packageName = lazy ? "bootuiinventoryhidden" : "bootuiinventoryapp";
        String typeName = packageName + ".Reloaded";
        try (URLClassLoader oldLoader = loader(args[0], args[2]);
                URLClassLoader newLoader = loader(args[1], args[2])) {
            Thread.currentThread().setContextClassLoader(oldLoader);
            boolean previouslyTracked = !args[2].startsWith("unseen");
            long oldToken = InventoryBehaviors.claim(previouslyTracked ? List.of("inventory") : List.of());
            if (previouslyTracked) {
                InventoryBehaviors.awaitSelfTest();
            }
            Class<?> oldType = oldLoader.loadClass(typeName);
            Object oldObject = oldType.getConstructor().newInstance();
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Object> oldResult = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread task = new Thread(() -> {
                InventoryBehaviors.CONTEXT.set(new String[] {InventoryBehaviors.REQUEST, "/old"});
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("reload never released the old task");
                    }
                    oldResult.set(oldType.getMethod("run").invoke(oldObject));
                } catch (Throwable ex) {
                    failure.set(ex);
                } finally {
                    InventoryBehaviors.CONTEXT.remove();
                }
            });
            task.start();
            InventoryBehaviors.bridge.getMethod("disarm", long.class).invoke(null, oldToken);
            // Reload frameworks define their entry class before the early claim.
            Class<?> newType = newLoader.loadClass(typeName);
            Thread.currentThread().setContextClassLoader(newLoader);
            long token = InventoryBehaviors.claim();
            InventoryBehaviors.awaitSelfTest();
            if (lazy) {
                oldLoader.loadClass("bootuiinventoryapp.Lazy");
                InventoryBehaviors.refine(token, packageName);
            }
            long generation = (Long)
                    InventoryBehaviors.inventory.getMethod("currentGeneration").invoke(null);
            int id = InventoryBehaviors.id(typeName + "#run()I");
            start.countDown();
            task.join(10_000);
            InventoryBehaviors.drain(token);
            require(
                    !task.isAlive()
                            && failure.get() == null
                            && Integer.valueOf(1).equals(oldResult.get()),
                    "the retained task ran the old body");
            require(
                    !InventoryBehaviors.executed(id)
                            && InventoryBehaviors.firstHits(id).isEmpty(),
                    "old body never executes the changed method in the new run");

            Instrumentation instrumentation = (Instrumentation) Class.forName("net.bytebuddy.agent.ByteBuddyAgent")
                    .getMethod("getInstrumentation")
                    .invoke(null);
            instrumentation.retransformClasses(oldType);
            require(
                    Integer.valueOf(1).equals(oldType.getMethod("run").invoke(oldObject)),
                    "retransformed old body still behaves");
            InventoryBehaviors.drain(token);
            require(
                    !InventoryBehaviors.executed(id)
                            && InventoryBehaviors.firstHits(id).isEmpty(),
                    "retransform cannot rearm a stale definition");

            InventoryBehaviors.CONTEXT.set(new String[] {"00000000000000cd", "/new"});
            Object newObject = newType.getConstructor().newInstance();
            require(
                    Integer.valueOf(2).equals(newType.getMethod("run").invoke(newObject)),
                    "the replacement ran the changed body");
            InventoryBehaviors.drain(token);
            require(
                    InventoryBehaviors.executed(id)
                            && InventoryBehaviors.firstHits(id).size() == 1
                            && InventoryBehaviors.firstHits(id).get(0)[2] == generation
                            && InventoryBehaviors.firstHits(id).get(0)[5] == 0xcdL,
                    "only the new definition records the new run and request");
            System.out.println("RELOAD=ok");
            System.out.println("STATUS=" + InventoryBehaviors.status());
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            InventoryBehaviors.CONTEXT.remove();
        }
    }

    private static URLClassLoader loader(String jar, String mode) throws Exception {
        URL[] urls = {Path.of(jar).toUri().toURL()};
        return "equal".equals(mode)
                ? new EqualLoader(urls)
                : new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
    }

    private static final class EqualLoader extends URLClassLoader {

        EqualLoader(URL[] urls) {
            super(urls, ClassLoader.getPlatformClassLoader());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualLoader;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    private static void require(boolean condition, String behavior) {
        if (!condition) {
            throw new AssertionError(behavior);
        }
        System.out.println("PASS " + behavior);
    }
}
