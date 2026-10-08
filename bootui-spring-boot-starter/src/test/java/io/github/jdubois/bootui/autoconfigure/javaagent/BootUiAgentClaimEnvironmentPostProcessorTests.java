package io.github.jdubois.bootui.autoconfigure.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentSensorSettings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
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
    void aSideEffectsSensorThisVersionDoesNotShipIsAcceptedAndClaimed() {
        SpringApplication application = new SpringApplication(SampleApplication.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("bootui.enabled", "ON")
                .withProperty("bootui.agent.sensors", "executors,network");

        withBridge.postProcessEnvironment(environment, application);

        assertThat((List<String>) FakeBridge.REQUESTS.get(0).get("sensors")).containsExactly("executors", "network");
    }

    @Test
    void anUnknownSensorIdFailsTheStart() {
        SpringApplication application = new SpringApplication(SampleApplication.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("bootui.enabled", "ON")
                .withProperty("bootui.agent.sensors", "executors,proceses");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> withBridge.postProcessEnvironment(environment, application))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("proceses")
                .hasMessageContaining("processes");
        assertThat(FakeBridge.REQUESTS).as("nothing was claimed").isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theClaimAsksForTheConfiguredSensorsAndExecutorsOptions() {
        SpringApplication application = new SpringApplication(SampleApplication.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("bootui.enabled", "ON")
                .withProperty("bootui.agent.executors.skip-tasks", "com.acme.Wrapper")
                .withProperty("bootui.agent.executors.skip-threads", "worker-, other-")
                .withProperty("bootui.agent.executors.max-handoff", "30s")
                .withProperty("bootui.agent.ring-capacity", "5000");

        withBridge.postProcessEnvironment(environment, application);

        Map<String, Object> request = FakeBridge.REQUESTS.get(0);
        assertThat(request)
                .containsEntry(
                        "sensors",
                        List.of(
                                "executors",
                                "inventory",
                                "code-paths",
                                "processes",
                                "network",
                                "blocking",
                                "resources"));
        assertThat(request).as("rounded up to a power of two").containsEntry("ringCapacity", 8192);
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
    void bootUiForcedOnInAProductionProfileReleasesTheAgentInsteadOfClaimingIt() {
        SpringApplication application = new SpringApplication(SampleApplication.class);
        int initializers = application.getInitializers().size();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("bootui.enabled", "ON")
                .withProperty("bootui.agent.mode", "dev")
                .withProperty("spring.application.name", "petclinic");
        environment.setActiveProfiles("prod");

        withBridge.postProcessEnvironment(environment, application);

        assertThat(FakeBridge.CALLS).containsExactly("release dev:petclinic");
        assertThat(application.getInitializers()).hasSize(initializers);
        assertThat(AgentProfileGuard.refusal(
                        io.github.jdubois.bootui.autoconfigure.BootUiActivationCondition.resolve(
                                environment, getClass().getClassLoader()),
                        environment))
                .contains("disabled profile 'prod'", AgentProfileGuard.ALLOW_IN_DISABLED_PROFILES + "=true");
    }

    @Test
    void anExplicitOptInClaimsTheAgentInADisabledProfile() {
        SpringApplication application = new SpringApplication(SampleApplication.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("bootui.enabled", "ON")
                .withProperty(AgentProfileGuard.ALLOW_IN_DISABLED_PROFILES, "true")
                .withProperty("spring.application.name", "petclinic");
        environment.setActiveProfiles("production");

        withBridge.postProcessEnvironment(environment, application);

        assertThat(FakeBridge.CALLS).containsExactly("claim");
        assertThat(AgentProfileGuard.refusal(
                        io.github.jdubois.bootui.autoconfigure.BootUiActivationCondition.resolve(
                                environment, getClass().getClassLoader()),
                        environment))
                .isNull();
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

    /**
     * Spring Cloud's bootstrap context runs a {@code SpringApplication} inside the application's while its environment
     * is prepared, without the application's configuration, so BootUI can resolve to disabled there: that nested run
     * must not release the claim the application then makes, or keeps armed across DevTools restarts, nor claim with its
     * own sources' packages when it resolves to enabled.
     */
    @Test
    void aSpringApplicationRunInsideAnotherNeitherReleasesNorClaims() {
        for (String nestedActivation : List.of("OFF", "ON")) {
            FakeBridge.reset();
            SpringApplication application = plainApplication(SampleApplication.class, "ON");
            application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event -> {
                SpringApplication bootstrap = plainApplication(NestedConfiguration.class, nestedActivation);
                bootstrap.addListeners(postProcessOn());
                bootstrap.run().close();
            });
            application.addListeners(postProcessOn());

            application.run().close();

            assertThat(FakeBridge.CALLS)
                    .as("with the nested run resolving BootUI %s", nestedActivation)
                    .containsExactly("claim", "refine", "disarm");
            assertThat(FakeBridge.REQUESTS.get(0)).containsEntry("application", "petclinic");
        }
    }

    @Test
    void aSingleRunIsNotNested() {
        SpringApplication application = plainApplication(SampleApplication.class, "ON");
        application.addListeners(postProcessOn());

        application.run().close();

        assertThat(FakeBridge.CALLS).containsExactly("claim", "refine", "disarm");
        assertThat(BootUiAgentClaimEnvironmentPostProcessor.nestedRun()).isFalse();
    }

    @Test
    void springCloudsBootstrapEnvironmentNeitherReleasesNorClaims() {
        for (String activation : List.of("OFF", "ON")) {
            FakeBridge.reset();
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("bootui.enabled", activation)
                    .withProperty("spring.application.name", "petclinic");
            environment
                    .getPropertySources()
                    .addLast(new org.springframework.core.env.MapPropertySource("bootstrap", Map.of()));

            withBridge.postProcessEnvironment(environment, new SpringApplication(SampleApplication.class));

            assertThat(FakeBridge.CALLS).as("BootUI %s", activation).isEmpty();
        }
    }

    /** A run without web server, banner, or auto-configuration, named petclinic as Spring Cloud applications are. */
    private static SpringApplication plainApplication(Class<?> source, String bootUi) {
        SpringApplication application = new SpringApplication(source);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        application.setDefaultProperties(Map.of(
                "bootui.enabled",
                bootUi,
                "bootui.agent.mode",
                "dev",
                "spring.application.name",
                "petclinic",
                "bootui.force-web",
                "false"));
        return application;
    }

    /** Calls the post-processor bound to the fake bridge as the run's environment is prepared. */
    private ApplicationListener<ApplicationEnvironmentPreparedEvent> postProcessOn() {
        return event -> withBridge.postProcessEnvironment(event.getEnvironment(), event.getSpringApplication());
    }

    @Configuration(proxyBeanMethods = false)
    static class NestedConfiguration {}

    static class SampleApplication {}
}
