package io.github.jdubois.bootui.engine.crac.fixtures;

import java.io.FileInputStream;
import org.crac.Context;
import org.crac.Resource;

/**
 * Delegates the close call to a helper on a <em>different</em> class ({@link CleanupHelper}), not
 * a private helper declared on this class. This must still be flagged by {@code CRAC-RES-001}: the
 * traversal only recognizes cleanup performed directly, or delegated to a private helper on the
 * same class, not calls into arbitrary collaborators.
 */
public class ManagedCrossClassHelperDelegation implements Resource {

    private FileInputStream input;

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
        CleanupHelper.close(input);
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) throws Exception {
        input = new FileInputStream("cross-class-helper.txt");
    }

    static final class CleanupHelper {

        private CleanupHelper() {}

        static void close(FileInputStream input) throws Exception {
            if (input != null) {
                input.close();
            }
        }
    }
}
