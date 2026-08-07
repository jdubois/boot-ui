package io.github.jdubois.bootui.engine.crac.fixtures;

/** Holds an unstarted thread, which is ordinary heap state rather than active background work. */
public class UnstartedThreadHolder {

    private final Thread thread = new Thread(() -> {});

    public Thread thread() {
        return thread;
    }
}
