package io.github.jdubois.bootui.quarkus.it;

import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * A blocking endpoint used only by {@link BootUiQuarkusRuntimeJournalTest}: it burns at least 20 ms of CPU on its
 * worker thread and returns the CPU time it measured there, so the test can check that the request's measured CPU
 * covers its worker segment ({@code docs/PLAN-v2.md} §5.11).
 */
@Path("/it/cpu")
public class CpuProbeResource {

    @GET
    @Blocking
    @Produces(MediaType.TEXT_PLAIN)
    public String burn() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long start = threads.getCurrentThreadCpuTime();
        long sink = 0;
        while (threads.getCurrentThreadCpuTime() - start < 20_000_000L) {
            for (int i = 0; i < 10_000; i++) {
                sink += Long.toString(i).hashCode();
            }
        }
        return (threads.getCurrentThreadCpuTime() - start) + ":"
                + (sink == 42 ? "" : Thread.currentThread().getName());
    }
}
