package io.github.bootui.sample;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EchoScheduler {

    @Scheduled(fixedRate = 30_000)
    public void echo() {
        System.out.println("echo");
    }
}
