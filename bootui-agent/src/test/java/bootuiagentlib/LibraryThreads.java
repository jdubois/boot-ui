package bootuiagentlib;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Library code outside the claimed packages, for the threads sensor's behaviors (PLAN-v2 M5-2c): threads it starts
 * inside a request are never propagated, even when their task is an application lambda.
 */
public final class LibraryThreads {

    private LibraryThreads() {}

    /** A pool filler: the library starts a worker from the application's thread factory. */
    public static Thread startFromFactory(ThreadFactory factory, Runnable task) {
        Thread thread = factory.newThread(task);
        thread.start();
        return thread;
    }

    /** A framework dispatching a handler to a new virtual thread (JDK 21+), as Quarkus does for virtual-thread routes. */
    public static Thread startVirtual(Runnable task) throws ReflectiveOperationException {
        Object builder = Thread.class.getMethod("ofVirtual").invoke(null);
        return (Thread) Class.forName("java.lang.Thread$Builder")
                .getMethod("start", Runnable.class)
                .invoke(builder, task);
    }

    /** A context-propagating executor wrapper, as Micrometer's: it carries the context itself. */
    public static <T> Future<T> submitWithContext(
            ExecutorService delegate, Supplier<String> current, Consumer<String> restore, Callable<T> task) {
        String captured = current.get();
        return delegate.submit(() -> {
            restore.accept(captured);
            try {
                return task.call();
            } finally {
                restore.accept(null);
            }
        });
    }
}
