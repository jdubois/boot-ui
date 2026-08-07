package io.github.jdubois.bootui.engine.crac.fixtures;

import java.io.FileInputStream;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.crac.Context;
import org.crac.Resource;

/** Acquires state during beforeCheckpoint(), where managed-call-site exemptions must not apply. */
public class ManagedBeforeCheckpointAcquisition implements Resource {

    private final SecureRandom random = new SecureRandom();
    private ServerSocket socket;
    private FileInputStream file;
    private ExecutorService executor;

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
        socket = new ServerSocket(0);
        file = new FileInputStream("data.txt");
        executor = Executors.newSingleThreadExecutor();
        random.setSeed(new byte[] {1, 2, 3});
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) {}
}
