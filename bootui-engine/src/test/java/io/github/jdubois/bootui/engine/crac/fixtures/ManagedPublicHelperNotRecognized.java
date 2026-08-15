package io.github.jdubois.bootui.engine.crac.fixtures;

import java.io.FileInputStream;
import org.crac.Context;
import org.crac.Resource;

/**
 * {@code beforeCheckpoint()} delegates to a <b>public</b> same-class method that performs the
 * close. Delegation recognition is intentionally restricted to <em>private</em> helper methods, so
 * this broader-visibility method (which could be called from elsewhere for unrelated reasons) must
 * not be treated as cleanup evidence and the field must still be flagged by {@code CRAC-RES-001}.
 */
public class ManagedPublicHelperNotRecognized implements Resource {

    private FileInputStream input;

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
        closeInput();
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) throws Exception {
        input = new FileInputStream("public-helper.txt");
    }

    public void closeInput() throws Exception {
        if (input != null) {
            input.close();
        }
    }
}
