package io.github.jdubois.bootui.engine.crac.fixtures;

import java.io.IOException;
import java.net.Socket;
import org.crac.Context;
import org.crac.Resource;

/**
 * Implements {@code org.crac.Resource} but opens a socket from an overload that is not the real
 * {@link #afterRestore(Context)} callback. CRAC-NET-001's exemption validates the callback signature,
 * so this acquisition must still be flagged.
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

    public Socket afterRestore(String ignored) throws IOException {
        return new Socket("localhost", 9090);
    }
}
