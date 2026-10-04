package io.github.jdubois.bootui.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * The sample, started by {@link SpringAgentDevToolsRestartIT} in a JVM of its own with the BootUI agent and DevTools'
 * restarts ({@code docs/PLAN-v2.md} M5-1). Each run started in a DevTools restart class loader loads its own numbered
 * {@link RestartSentinel} once it is ready; the first launch, which DevTools stops before its context starts, loads
 * none.
 */
public final class DevToolsRestartApplication {

    private DevToolsRestartApplication() {}

    public static void main(String[] args) {
        // A database of its own per run: DevTools shuts the previous run's in-memory database down as the next run
        // starts, and a run opening the same one meanwhile fails with "Database is already closed".
        int launch = Integer.getInteger("bootui.sample.restart-launch", 0) + 1;
        System.setProperty("bootui.sample.restart-launch", String.valueOf(launch));
        System.setProperty(
                "spring.datasource.url",
                "jdbc:h2:mem:bootui_restart_" + launch + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false");
        SpringApplication application = new SpringApplication(BootUiSampleApplication.class);
        application.addListeners(new LoadSentinel());
        application.run(args);
    }

    static final class LoadSentinel implements ApplicationListener<ApplicationReadyEvent> {

        @Override
        public void onApplicationEvent(ApplicationReadyEvent event) {
            System.out.println("RESTART_RUN=" + RestartSentinel.run());
        }
    }
}
