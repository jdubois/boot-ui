package io.github.jdubois.bootui.engine.crac.fixtures;

import java.io.FileInputStream;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.crac.Context;
import org.crac.Resource;

/**
 * Implements {@code org.crac.Resource} and re-acquires its socket, file handle, executor pool, and
 * HTTP client inside {@link #afterRestore(Context)} - exactly the pattern that CRAC-NET-001,
 * CRAC-FILE-001, CRAC-THREAD-001, and CRAC-POOL-002 recommend. None of these calls/fields should be
 * flagged: the class implements {@code org.crac.Resource}, so it is a managed class for the purposes
 * of {@code ManagedLifecycleCallSites}.
 */
public class ManagedResourceLifecycle implements Resource {

    private ServerSocket socket;
    private FileInputStream file;
    private ExecutorService pool;
    private HttpClient httpClient;

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
        if (socket != null) {
            socket.close();
        }
        if (file != null) {
            file.close();
        }
        if (pool != null) {
            pool.shutdown();
        }
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) throws Exception {
        socket = new ServerSocket(8082);
        file = new FileInputStream("data.txt");
        pool = Executors.newFixedThreadPool(2);
        httpClient = HttpClient.newHttpClient();
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }
}
