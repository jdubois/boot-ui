package io.github.jdubois.bootui.engine.support;

import java.lang.reflect.Method;
import java.security.PrivilegedAction;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Creates BootUI's long-lived daemon threads so they keep nothing of the application that happened to start them, the
 * same way the BootUI agent creates its own. A JVM-wide pool's worker outlives a DevTools restart or a Quarkus live
 * reload, and a thread created the plain way would keep the run that created it reachable: through its context class
 * loader, through the values of inheritable thread-locals, and on JDK 17 to 23 through the access control context of the
 * creating stack, whose protection domains reference the class loaders of its frames. These threads therefore inherit no
 * thread-locals, get a fixed context class loader, and are created in a privileged block, which limits the captured
 * context to BootUI's own frames. {@code AccessController} is reached reflectively: it is deprecated for removal and
 * a no-op from JDK 24.
 */
public final class BootUiThreads {

    /** BootUI's own class loader: a context class loader that pins nothing a pool does not already pin. */
    public static final ClassLoader ENGINE_LOADER = BootUiThreads.class.getClassLoader();

    private static final System.Logger LOG = System.getLogger(BootUiThreads.class.getName());
    private static final Method DO_PRIVILEGED = doPrivileged();

    private BootUiThreads() {}

    /** A factory of daemon threads named {@code prefix} and a sequence number, with {@code contextLoader}. */
    public static ThreadFactory daemonFactory(String prefix, ClassLoader contextLoader) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> newDaemonThread(prefix + sequence.incrementAndGet(), task, contextLoader);
    }

    /** An unstarted daemon thread that inherits nothing from the calling thread, with {@code contextLoader}. */
    public static Thread newDaemonThread(String name, Runnable task, ClassLoader contextLoader) {
        Supplier<Thread> creation = () -> {
            Thread thread = new Thread(null, task, name, 0, false);
            thread.setDaemon(true);
            thread.setContextClassLoader(contextLoader);
            return thread;
        };
        if (DO_PRIVILEGED == null) {
            return creation.get();
        }
        PrivilegedAction<Thread> action = creation::get;
        try {
            return (Thread) DO_PRIVILEGED.invoke(null, action);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // Still free of the caller's context class loader and thread-locals, not of its access control context.
            LOG.log(System.Logger.Level.DEBUG, "BootUI created thread " + name + " outside a privileged block", ex);
            return creation.get();
        }
    }

    /**
     * Runs {@code task} with {@code contextLoader} as the current thread's context class loader, then restores the
     * previous one, also when {@code task} changed it. A {@code null} loader leaves the context class loader as it is.
     */
    public static void runWithContextLoader(ClassLoader contextLoader, Runnable task) {
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        if (contextLoader != null) {
            current.setContextClassLoader(contextLoader);
        }
        try {
            task.run();
        } finally {
            current.setContextClassLoader(previous);
        }
    }

    private static Method doPrivileged() {
        if (Runtime.version().feature() >= 24) {
            return null;
        }
        try {
            return Class.forName("java.security.AccessController").getMethod("doPrivileged", PrivilegedAction.class);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            return null;
        }
    }
}
