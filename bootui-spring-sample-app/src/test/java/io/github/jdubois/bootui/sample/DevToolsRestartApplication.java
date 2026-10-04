package io.github.jdubois.bootui.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;

/**
 * The sample, started by {@link SpringAgentDevToolsRestartIT} in a JVM of its own with the BootUI agent and DevTools'
 * restarts ({@code docs/PLAN-v2.md} M5-1). Each run started in a DevTools restart class loader loads its own numbered
 * {@link RestartSentinel}; the first launch, which DevTools stops before its context starts, loads none.
 */
public final class DevToolsRestartApplication {

    private DevToolsRestartApplication() {}

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(BootUiSampleApplication.class);
        application.addListeners(new LoadSentinel());
        application.run(args);
    }

    static final class LoadSentinel implements ApplicationListener<ApplicationStartedEvent> {

        @Override
        public void onApplicationEvent(ApplicationStartedEvent event) {
            System.out.println("RESTART_RUN=" + RestartSentinel.run());
        }
    }
}
