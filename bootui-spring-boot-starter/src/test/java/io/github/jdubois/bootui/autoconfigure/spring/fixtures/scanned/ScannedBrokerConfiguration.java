package app.advisoraudit.scanned;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurationSupport;

/** Component-scanned (ASM-parsed) broker configuration covariantly overriding one executor without {@code @Bean}. */
@Configuration
public class ScannedBrokerConfiguration extends WebSocketMessageBrokerConfigurationSupport {
    @Override
    protected void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws");
    }

    @Override
    public ThreadPoolTaskExecutor clientInboundChannelExecutor() {
        return new ThreadPoolTaskExecutor();
    }
}
