package io.github.jdubois.bootui.engine.mcp;

/**
 * Runs a delegate from a class that a test redefines in a throwaway class loader, so a frame of that loader is on the
 * stack, as an application class is under a DevTools restart class loader.
 */
public final class ApplicationFrameProbe implements Runnable {

    private final Runnable delegate;

    public ApplicationFrameProbe(Runnable delegate) {
        this.delegate = delegate;
    }

    @Override
    public void run() {
        delegate.run();
    }
}
