package io.github.jdubois.bootui.engine.crac.fixtures;

import java.io.FileInputStream;
import org.crac.Context;
import org.crac.Resource;

/**
 * Delegates cleanup two helper calls deep: {@code beforeCheckpoint()} calls {@link #helperA()},
 * which calls {@link #helperB()}, which finally closes the field. Exercises transitive (not just
 * one-hop) delegation recognition.
 */
public class ManagedTransitiveHelperCleanup implements Resource {

    private FileInputStream input;

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
        helperA();
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) throws Exception {
        input = new FileInputStream("transitive-helper.txt");
    }

    private void helperA() throws Exception {
        helperB();
    }

    private void helperB() throws Exception {
        if (input != null) {
            input.close();
        }
    }
}
