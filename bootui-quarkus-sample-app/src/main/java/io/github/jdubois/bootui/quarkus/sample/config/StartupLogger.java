package io.github.jdubois.bootui.quarkus.sample.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

/** Logs a startup marker (mirrors the Spring sample's ApplicationReadyEvent listener). */
@ApplicationScoped
public class StartupLogger {

    private static final Logger LOG = Logger.getLogger(StartupLogger.class);

    void onStart(@Observes StartupEvent event) {
        LOG.info("BootUI Quarkus sample app started");
    }
}
