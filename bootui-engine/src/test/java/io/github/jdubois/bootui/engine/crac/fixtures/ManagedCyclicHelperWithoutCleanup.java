package io.github.jdubois.bootui.engine.crac.fixtures;

import java.io.FileInputStream;
import org.crac.Context;
import org.crac.Resource;

/**
 * {@code beforeCheckpoint()} delegates through a pair of private helpers that call each other
 * ({@link #helperA()} &lt;-&gt; {@link #helperB()}) without ever closing the field. Confirms the
 * visited-call guard terminates on a cyclic call graph and the field is still correctly flagged
 * as lacking observable cleanup, rather than the traversal looping or false-clearing the finding.
 */
public class ManagedCyclicHelperWithoutCleanup implements Resource {

    private FileInputStream input;

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) {
        helperA();
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) throws Exception {
        input = new FileInputStream("cyclic-helper.txt");
    }

    private void helperA() {
        helperB();
    }

    private void helperB() {
        helperA();
    }

    public FileInputStream input() {
        return input;
    }
}
