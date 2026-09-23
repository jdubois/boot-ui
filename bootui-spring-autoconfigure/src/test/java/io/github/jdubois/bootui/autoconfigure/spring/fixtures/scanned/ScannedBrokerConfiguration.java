package app.advisoraudit.scanned;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurationSupport;

/** Component-scanned (ASM-parsed) broker configuration overriding one inherited executor without {@code @Bean}. */
@Configuration
public class ScannedBrokerConfiguration extends WebSocketMessageBrokerConfigurationSupport {
    @Override
    protected void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws");
    }

    @Override
    public Executor clientInboundChannelExecutor() {
        return new ThreadPoolTaskExecutor();
    }
}
