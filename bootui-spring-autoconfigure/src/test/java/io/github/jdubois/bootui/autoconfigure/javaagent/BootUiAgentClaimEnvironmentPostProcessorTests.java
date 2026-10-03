package io.github.jdubois.bootui.autoconfigure.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentSensorSettings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class BootUiAgentClaimEnvironmentPostProcessorTests {

    private final BootUiAgentClaimEnvironmentPostProcessor withBridge =
            new BootUiAgentClaimEnvironmentPostProcessor(() -> AgentBridgeAccess.bind(FakeBridge.class));

    @BeforeEach
    void reset() {
        FakeBridge.reset();
    }

    @Test
    void withoutTheAgentItDoesNothing() {
        SpringApplication application = new SpringApplication(SampleApplication.class);
        int initializers = application.getInitializers().size();
        int listeners = application.getListeners().size();

        new BootUiAgentClaimEnvironmentPostProcessor(AgentBridgeAccess::absent)
                .postProcessEnvironment(new MockEnvironment().withProperty("bootui.enabled", "ON"), application);

        assertThat(application.getInitializers()).hasSize(initializers);
        assertThat(application.getListeners()).hasSize(listeners);
        assertThat(FakeBridge.CALLS).isEmpty();
    }

    @Test
    void anActiveBootUiClaimsTheAgentForThisRunAndHandsTheClaimToAnOwner() {
        SpringApplication application = new SpringApplication(SampleApplication.class);
        application.setMainApplicationClass(SampleApplication.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("bootui.enabled", "ON")
                .withProperty("spring.application.name", "petclinic")
                .withProperty("bootui.agent.packages", "org.acme.shared, com.example.more");

        withBridge.postProcessEnvironment(environment, application);

        assertThat(FakeBridge.CALLS).containsExactly("claim");
        assertThat(FakeBridge.REQUESTS.get(0))
                .containsEntry("application", "petclinic")
                .containsEntry("mode", "test")
                .containsEntry(
                        "packages",
                        List.of(
                                "io.github.jdubois.bootui.autoconfigure.javaagent",
                                "org.acme.shared",
                                "com.example.more"));
        assertThat((String) FakeBridge.REQUESTS.get(0).get("owner")).matches("petclinic@[0-9a-f]{8}");
        AgentClaimOwner owner = application.getInitializers().stream()
                .filter(AgentClaimOwner.class::isInstance)
                .map(AgentClaimOwner.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(application.getListeners()).contains(owner);
        assertThat(owner.claim().armed()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theClaimAsksForTheConfiguredSensorsAndExecutorsOptions() {
        SpringApplication application = new SpringApplication(SampleApplication.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("bootui.enabled", "ON")
                .withProperty("bootui.agent.executors.skip-tasks", "com.acme.Wrapper")
                .withProperty("bootui.agent.executors.skip-threads", "worker-, other-")
                .withProperty("bootui.agent.executors.max-handoff", "30s");

        withBridge.postProcessEnvironment(environment, application);

        Map<String, Object> request = FakeBridge.REQUESTS.get(0);
        assertThat(request).containsEntry("sensors", List.of("executors"));
        assertThat((Map<String, Object>) request.get("executors"))
                .containsEntry("skipTasks", List.of("com.acme.Wrapper"))
                .containsEntry("skipThreads", List.of("worker-", "other-"));
        AgentClaimOwner owner = application.getInitializers().stream()
                .filter(AgentClaimOwner.class::isInstance)
                .map(AgentClaimOwner.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(owner.claim().sensors().maxHandoff()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void reactorSchedulerThreadsAreSkippedOnlyWhenReactorPropagatesTheContext() {
        AgentSensorSettings limited = BootUiAgentClaimEnvironmentPostProcessor.sensors(
                new MockEnvironment().withProperty("spring.reactor.context-propagation", "limited"));
        AgentSensorSettings auto = BootUiAgentClaimEnvironmentPostProcessor.sensors(
                new MockEnvironment().withProperty("spring.reactor.context-propagation", "auto"));

        assertThat(limited.skipThreads()).containsExactlyElementsOf(AgentSensorSettings.DEFAULT_SKIP_THREADS);
        assertThat(limited.skipTasks()).containsExactlyElementsOf(AgentSensorSettings.DEFAULT_SKIP_TASKS);
        assertThat(auto.skipThreads()).containsExactly("vert.x-", "bootui-", "parallel-", "boundedElastic-", "single-");
        assertThat(BootUiAgentClaimEnvironmentPostProcessor.sensors(
                                new MockEnvironment().withProperty("bootui.agent.sensors", ""))
                        .executors())
                .as("an empty sensor list asks for none")
                .isFalse();
    }

    @Test
    void aConfiguredModeWinsOverTheTestFrameworkOnTheStack() {
        SpringApplication application = new SpringApplication(SampleApplication.class);
        application.setMainApplicationClass(SampleApplication.class);

        withBridge.postProcessEnvironment(
                new MockEnvironment().withProperty("bootui.enabled", "ON").withProperty("bootui.agent.mode", "dev"),
                application);

        assertThat(FakeBridge.REQUESTS.get(0))
                .containsEntry("mode", "dev")
                .containsEntry("application", SampleApplication.class.getName());
        assertThat(BootUiAgentClaimEnvironmentPostProcessor.startedByTestFramework())
                .isTrue();
    }

    @Test
    void aDisabledBootUiReleasesTheAgentWithoutAnOwner() {
        SpringApplication application = new SpringApplication(SampleApplication.class);
        int initializers = application.getInitializers().size();

        withBridge.postProcessEnvironment(
                new MockEnvironment()
                        .withProperty("bootui.enabled", "OFF")
                        .withProperty("spring.application.name", "petclinic"),
                application);

        assertThat(FakeBridge.CALLS).containsExactly("release test:petclinic");
        assertThat(application.getInitializers()).hasSize(initializers);
    }

    @Test
    void disablingTheAgentSupportReleasesItToo() {
        SpringApplication application = new SpringApplication(SampleApplication.class);

        withBridge.postProcessEnvironment(
                new MockEnvironment()
                        .withProperty("bootui.enabled", "ON")
                        .withProperty("bootui.agent.enabled", "false")
                        .withProperty("bootui.agent.mode", "dev")
                        .withProperty("spring.application.name", "petclinic"),
                application);

        assertThat(FakeBridge.CALLS).containsExactly("release dev:petclinic");
    }

    @Test
    void inATestALauncherDeducedAsTheMainClassIsNeitherTheNameNorAClaimedPackage() {
        SpringApplication application = new SpringApplication(SampleApplication.class);
        application.setMainApplicationClass(org.springframework.boot.SpringApplication.class);

        assertThat(BootUiAgentClaimEnvironmentPostProcessor.packages(new MockEnvironment(), application, "test"))
                .containsExactly("io.github.jdubois.bootui.autoconfigure.javaagent");
        assertThat(BootUiAgentClaimEnvironmentPostProcessor.applicationName(new MockEnvironment(), application))
                .isEqualTo(SampleApplication.class.getName());
    }

    @Test
    void everyTestClassOfAnApplicationSharesTheConfigurationClassAsItsName() {
        SpringApplication application = new SpringApplication(SampleApplication.class);
        application.setMainApplicationClass(BootUiAgentClaimEnvironmentPostProcessorTests.class);

        assertThat(BootUiAgentClaimEnvironmentPostProcessor.applicationName(new MockEnvironment(), application))
                .isEqualTo(SampleApplication.class.getName());
    }

    @Test
    void aMalformedPropertyNeverBreaksStartUp() {
        SpringApplication application = new SpringApplication(SampleApplication.class);

        withBridge.postProcessEnvironment(
                new MockEnvironment()
                        .withProperty("bootui.enabled", "ON")
                        .withProperty("bootui.agent.packages[x]", "y"),
                application);
    }

    @Test
    void runsLastAmongEnvironmentPostProcessors() {
        assertThat(withBridge.getOrder()).isEqualTo(org.springframework.core.Ordered.LOWEST_PRECEDENCE);
    }

    static class SampleApplication {}
}
