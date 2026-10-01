package io.github.jdubois.bootui.engine.graalvm.fixtures;

/** Empty and super-only finalizers carry no cleanup; GRAAL-JDK-003 must stay quiet. */
public class FinalizerGuard {

    @Override
    @SuppressWarnings({"deprecation", "removal"})
    protected final void finalize() {}

    public static class SuperOnly {
        @Override
        @SuppressWarnings({"deprecation", "removal"})
        protected void finalize() throws Throwable {
            super.finalize();
        }
    }
}
