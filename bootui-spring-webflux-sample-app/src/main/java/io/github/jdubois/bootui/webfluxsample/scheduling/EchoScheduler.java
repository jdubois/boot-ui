package io.github.jdubois.bootui.webfluxsample.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Trivial recurring task so the Scheduled Tasks panel has something to list. */
@Component
public class EchoScheduler {

    private static final Logger logger = LoggerFactory.getLogger(EchoScheduler.class);

    @Scheduled(fixedRate = 30_000)
    public void echo() {
        logger.info("echo");
    }
}
