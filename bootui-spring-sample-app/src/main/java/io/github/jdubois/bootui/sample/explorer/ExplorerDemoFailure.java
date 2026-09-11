package io.github.jdubois.bootui.sample.explorer;

/** One throwable unwinds through repository and service; advice must not record extra occurrences. */
public class ExplorerDemoFailure extends RuntimeException {
    public ExplorerDemoFailure() {
        super("Explicit Explorer sample failure");
    }
}
