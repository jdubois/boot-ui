package io.github.jdubois.bootui.engine.crac.fixtures;

import java.io.IOException;
import java.net.Socket;
import org.crac.Context;
import org.crac.Resource;

/**
 * Implements {@code org.crac.Resource} but opens a socket from an unrelated method rather than from
 * {@link #beforeCheckpoint(Context)}/{@link #afterRestore(Context)}. CRAC-NET-001's exemption is
 * scoped to calls that originate from the managed callback methods themselves, not to the whole
 * class, so this leak must still be flagged.
 */
public class ManagedClassWithUnrelatedLeak implements Resource {

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) {
        // No resource to release here; the leak below is intentionally outside this callback.
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) {
        // No resource to re-acquire here; the leak below is intentionally outside this callback.
    }

    public Socket connect() throws IOException {
        return new Socket("localhost", 9090);
    }
}
