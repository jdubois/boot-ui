package io.github.jdubois.bootui.engine.crac.fixtures;

import java.io.FileInputStream;
import java.io.IOException;
import org.crac.Context;
import org.crac.Resource;

/** Implements Resource but provides no observable cleanup for its resource field. */
public class ManagedClassWithoutCleanup implements Resource {

    private FileInputStream input;

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) {}

    public void beforeCheckpoint(String ignored) throws IOException {
        if (input != null) {
            input.close();
        }
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) {}

    public FileInputStream input() {
        return input;
    }
}
