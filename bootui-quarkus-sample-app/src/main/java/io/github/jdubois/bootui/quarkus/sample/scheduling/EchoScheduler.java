package io.github.jdubois.bootui.quarkus.sample.scheduling;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/** Periodic task so the Scheduled Tasks panel has a job to display (mirrors the Spring EchoScheduler). */
@ApplicationScoped
public class EchoScheduler {

    private static final Logger LOG = Logger.getLogger(EchoScheduler.class);

    @Scheduled(every = "30s")
    void echo() {
        LOG.info("echo");
    }
}
