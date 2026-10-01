package io.github.jdubois.bootui.engine.graalvm.fixtures;

/** Triggers GRAAL-JDK-003: cleanup in finalize() never runs in a native image. */
public abstract class FinalizingResource {

    private long handle = 1L;

    @Override
    @SuppressWarnings({"deprecation", "removal"})
    protected void finalize() throws Throwable {
        handle = 0L;
        super.finalize();
    }

    public long handle() {
        return handle;
    }
}
