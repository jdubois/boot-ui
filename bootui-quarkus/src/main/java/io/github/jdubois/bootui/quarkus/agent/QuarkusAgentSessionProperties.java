package io.github.jdubois.bootui.quarkus.agent;

import io.github.jdubois.bootui.spi.agent.AgentSessionProperties;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import org.eclipse.microprofile.config.Config;

/**
 * Quarkus base mapping of {@code bootui.<prefix>.*} MicroProfile {@code Config} onto the engine
 * {@link AgentSessionProperties} contract, mirroring the Spring {@code BootUiProperties.Copilot}
 * defaults exactly (enabled AUTO, 2000 events, 100 sessions/parsed, 400ms debounce). Subclasses supply
 * the panel-specific titles / directory layout.
 */
abstract class QuarkusAgentSessionProperties implements AgentSessionProperties {

    private final String prefix;
    private final Config config;

    QuarkusAgentSessionProperties(String prefix, Config config) {
        this.prefix = prefix;
        this.config = config;
    }

    private String mode() {
        return config.getOptionalValue("bootui." + prefix + ".enabled", String.class)
                .orElse("AUTO")
                .trim()
                .toUpperCase();
    }

    @Override
    public boolean enabledOn() {
        return "ON".equals(mode());
    }

    @Override
    public boolean enabledAuto() {
        return "AUTO".equals(mode());
    }

    @Override
    public String getSessionStateDir() {
        return config.getOptionalValue("bootui." + prefix + ".session-state-dir", String.class)
                .orElse(null);
    }

    @Override
    public int getMaxEventsPerSession() {
        return config.getOptionalValue("bootui." + prefix + ".max-events-per-session", Integer.class)
                .orElse(2000);
    }

    @Override
    public int getMaxSessions() {
        return config.getOptionalValue("bootui." + prefix + ".max-sessions", Integer.class)
                .orElse(100);
    }

    @Override
    public int getMaxParsedSessions() {
        return config.getOptionalValue("bootui." + prefix + ".max-parsed-sessions", Integer.class)
                .orElse(100);
    }

    @Override
    public Duration getStreamDebounce() {
        return config.getOptionalValue("bootui." + prefix + ".stream-debounce", Duration.class)
                .orElse(Duration.ofMillis(400));
    }

    @Override
    public String maxParsedSessionsPropertyName() {
        return "bootui." + prefix + ".max-parsed-sessions";
    }

    static Path home(String... segments) {
        return Paths.get(System.getProperty("user.home", ""), segments);
    }
}
