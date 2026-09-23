package app.advisoraudit;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurationSupport;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import reactor.core.publisher.Mono;

/** Application-package fixtures: BootUI's own classes must not become application evidence. */
public final class ApplicationFixtures {
    private ApplicationFixtures() {}

    public static class ParentCounter {
        public int inherited;
    }

    public static class Counter extends ParentCounter {
        public int count;
        public static int global;
        public final int fixed = 1;

        @Autowired
        public Object injected;
    }

    @ConfigurationProperties("audit.settings")
    public static class BoundCounter {
        public int configured;
    }

    @RestController
    public static class Handler {
        @GetMapping("/audit")
        public Mono<String> handle() {
            return Mono.just("ok");
        }
    }

    public static class QualifiedAsync {
        @Async("special")
        public void run() {}
    }

    public static class ProtectedQualifiedAsync {
        @Async("special")
        protected void run() {}
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    public static class AsyncConfiguration {}

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    public static class SchedulingConfiguration {
        @Bean
        public Tasks tasks() {
            return new Tasks();
        }

        @Bean
        public ThreadPoolTaskScheduler taskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            return scheduler;
        }
    }

    public static class Tasks {
        @Scheduled(initialDelay = 3_600_000, fixedDelay = 3_600_000)
        public void first() {}

        @Scheduled(initialDelay = 3_600_000, fixedDelay = 3_600_000)
        public void second() {}
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSocketMessageBroker
    public static class StompConfiguration implements WebSocketMessageBrokerConfigurer {
        @Override
        public void configureMessageBroker(MessageBrokerRegistry registry) {}

        @Override
        public void registerStompEndpoints(StompEndpointRegistry registry) {
            registry.addEndpoint("/ws");
        }
    }

    @Configuration(proxyBeanMethods = false)
    public static class ReportPoolConfiguration {
        @Bean
        public ThreadPoolTaskExecutor reportPool() {
            return new ThreadPoolTaskExecutor();
        }
    }

    @Configuration
    public static class OverridingBrokerConfiguration extends WebSocketMessageBrokerConfigurationSupport {
        @Override
        protected void registerStompEndpoints(StompEndpointRegistry registry) {
            registry.addEndpoint("/ws");
        }

        @Bean
        @Override
        public Executor clientInboundChannelExecutor() {
            return new ThreadPoolTaskExecutor();
        }
    }

    /** Same-named overload that is not the inherited framework factory method. */
    public static class OverloadingBrokerConfiguration extends WebSocketMessageBrokerConfigurationSupport {
        @Override
        protected void registerStompEndpoints(StompEndpointRegistry registry) {}

        public Executor clientOutboundChannelExecutor(String tag) {
            return new ThreadPoolTaskExecutor();
        }
    }

    /** Same-named overload whose return type cannot produce the pool. */
    public static class UnrelatedOverloadBrokerConfiguration extends WebSocketMessageBrokerConfigurationSupport {
        @Override
        protected void registerStompEndpoints(StompEndpointRegistry registry) {}

        public String clientOutboundChannelExecutor(String tag) {
            return tag;
        }
    }

    public static class AppPool extends ThreadPoolTaskExecutor {}

    /** Covariant application override of a framework @Bean method that does not repeat @Bean. */
    public static class CovariantBrokerConfiguration extends WebSocketMessageBrokerConfigurationSupport {
        @Override
        protected void registerStompEndpoints(StompEndpointRegistry registry) {}

        @Override
        public AppPool clientOutboundChannelExecutor() {
            return new AppPool();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.ComponentScan("app.advisoraudit.scanned")
    public static class ScanConfiguration {}
}
