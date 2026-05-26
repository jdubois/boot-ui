package io.github.jdubois.bootui.sample;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EchoScheduler {

    private static final Logger logger = LoggerFactory.getLogger(EchoScheduler.class);

    @Scheduled(fixedRate = 30_000)
    public void echo() {
        logger.info("echo");
    }
}
