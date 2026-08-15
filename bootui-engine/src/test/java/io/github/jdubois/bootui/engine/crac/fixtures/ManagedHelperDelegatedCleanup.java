package io.github.jdubois.bootui.engine.crac.fixtures;

import java.io.FileInputStream;
import org.crac.Context;
import org.crac.Resource;

/**
 * Implements {@code org.crac.Resource} and delegates the actual close call to a private helper
 * method invoked from {@link #beforeCheckpoint(Context)} instead of calling {@code close()}
 * directly. This is a common refactor and must still count as observable cleanup evidence for
 * {@code CRAC-RES-001}.
 */
public class ManagedHelperDelegatedCleanup implements Resource {

    private FileInputStream input;

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
        releaseInput();
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) throws Exception {
        input = new FileInputStream("helper-delegated.txt");
    }

    private void releaseInput() throws Exception {
        if (input != null) {
            input.close();
        }
    }
}
