package io.github.jdubois.bootui.engine.crac.fixtures;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Creates an executor pool outside the Spring lifecycle (CRAC-THREAD-001). */
public class ThreadStarter {

    public ExecutorService pool() {
        return Executors.newFixedThreadPool(2);
    }

    public void background() {
        new Thread(() -> {}).start();
    }
}
