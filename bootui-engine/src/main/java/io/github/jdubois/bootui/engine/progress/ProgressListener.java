package io.github.jdubois.bootui.engine.progress;

/**
 * Receives an operation's accepted progress events on the reporting thread. Implementations must return quickly and
 * never block or perform I/O: the reporting thread is the operation itself.
 */
@FunctionalInterface
public interface ProgressListener {

    void onProgress(ProgressEvent event);
}
