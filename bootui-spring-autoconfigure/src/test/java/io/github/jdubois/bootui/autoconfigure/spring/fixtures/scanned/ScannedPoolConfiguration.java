package app.advisoraudit.scanned;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class ScannedPoolConfiguration {
    @Bean
    public static ThreadPoolTaskExecutor staticPool() {
        return new ThreadPoolTaskExecutor();
    }
}
