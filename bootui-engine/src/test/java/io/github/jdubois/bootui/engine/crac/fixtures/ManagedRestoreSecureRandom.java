package io.github.jdubois.bootui.engine.crac.fixtures;

import java.security.SecureRandom;
import org.crac.Context;
import org.crac.Resource;

/** Reseeds a cached generator from the restore callback, which is the recommended ownership path. */
public class ManagedRestoreSecureRandom implements Resource {

    private final SecureRandom random = new SecureRandom();

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) {}

    @Override
    public void afterRestore(Context<? extends Resource> context) {
        random.setSeed(new byte[] {4, 5, 6});
    }
}
