package org.bootuiit.library;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A library outside the claimed packages that starts a thread and creates an executor on its caller's thread, as a
 * client or a framework pool does lazily inside a request: the thread-activity sensor names them the library's.
 */
public final class LibraryPool implements AutoCloseable {

    private final Thread thread;
    private final ThreadPoolExecutor executor;

    private LibraryPool(Thread thread, ThreadPoolExecutor executor) {
        this.thread = thread;
        this.executor = executor;
    }

    /** Starts the library's housekeeping thread and creates its executor. */
    public static LibraryPool start() {
        Thread thread = new Thread(LibraryPool::idle, "library-housekeeper");
        thread.setDaemon(true);
        thread.start();
        return new LibraryPool(thread, new ThreadPoolExecutor(0, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>()));
    }

    private static void idle() {
        try {
            Thread.sleep(5_000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() throws InterruptedException {
        thread.interrupt();
        thread.join();
        executor.shutdown();
    }
}
