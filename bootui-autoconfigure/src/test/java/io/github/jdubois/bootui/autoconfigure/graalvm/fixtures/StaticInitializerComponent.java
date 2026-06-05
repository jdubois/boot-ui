package io.github.jdubois.bootui.autoconfigure.graalvm.fixtures;

/** Triggers GRAAL-INIT-001 by starting a thread from a static initializer. */
public class StaticInitializerComponent {

    static {
        Thread worker = new Thread(() -> {
            // no-op background work
        });
        worker.start();
    }

    private StaticInitializerComponent() {}
}
