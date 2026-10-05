package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.SideEffects;
import java.security.PrivilegedAction;

/**
 * Creates the agent's threads, each marked as the agent's own when it starts so the side-effect sensors never record
 * what it does, and runs its installs so that nothing of the claiming stack is captured (PLAN-v2 D32): on
 * JDK 17 to 23 a new thread and Byte Buddy's transformer capture {@code AccessController.getContext()}, whose protection
 * domains would pin the class loader of the run that claimed first; inheritable thread-locals and the context class
 * loader would pin it too. {@code AccessController} is reached reflectively: it is deprecated for removal and a no-op
 * from JDK 24.
 */
final class AgentThreads {

    private AgentThreads() {}

    /** A daemon thread without inherited thread-locals or context class loader, created in a privileged block. */
    static Thread newThread(String name, Runnable task, boolean privileged) {
        Creation creation = new Creation(name, task);
        return privileged ? (Thread) privileged(creation) : creation.run();
    }

    /** Runs {@code action} in a privileged block on JDK 17 to 23, directly on JDK 24 and later. */
    static Object privileged(PrivilegedAction<?> action) {
        if (Runtime.version().feature() >= 24) {
            return action.run();
        }
        try {
            return Class.forName("java.security.AccessController")
                    .getMethod("doPrivileged", PrivilegedAction.class)
                    .invoke(null, action);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("cannot enter a privileged block", ex);
        }
    }

    static final class Creation implements PrivilegedAction<Thread> {

        private final String name;
        private final Runnable task;

        Creation(String name, Runnable task) {
            this.name = name;
            this.task = task;
        }

        @Override
        public Thread run() {
            Thread thread = new Thread(null, new Marked(task), name, 0, false);
            thread.setDaemon(true);
            thread.setContextClassLoader(null);
            return thread;
        }
    }

    /** Marks the thread as the agent's own for the side-effect sensors, then runs the task. */
    static final class Marked implements Runnable {

        private final Runnable task;

        Marked(Runnable task) {
            this.task = task;
        }

        @Override
        public void run() {
            SideEffects.markBootUiThread();
            task.run();
        }
    }
}
