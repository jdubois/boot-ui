package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class BootUiInactiveStartupDiagnosticTests {

    private final Logger logger = (Logger) LoggerFactory.getLogger(BootUiShellGuardAutoConfiguration.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureDiagnosticEvents() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void restoreLogger() {
        logger.detachAppender(events);
        events.stop();
        logger.setLevel(previousLevel);
    }

    @Test
    void ordinaryAutoInactivityLogsOnceWithALocalDevelopmentAction() {
        runner().run(context -> {
            assertSingleDiagnostic(
                    Level.INFO,
                    "BootUI is inactive in AUTO mode: no enabled profile is active and Spring Boot DevTools is absent."
                            + " For local development, activate a bootui.enabled-profiles profile or set bootui.enabled=ON.");
            // Neither repeatable condition evaluation nor additional lifecycle events log again.
            ConditionContext conditionContext = mock(ConditionContext.class);
            when(conditionContext.getEnvironment()).thenReturn(context.getEnvironment());
            when(conditionContext.getClassLoader()).thenReturn(context.getClassLoader());
            for (int i = 0; i < 3; i++) {
                assertThat(new BootUiActivationCondition().matches(conditionContext, null))
                        .isFalse();
                assertThat(new BootUiInactiveCondition().matches(conditionContext, null))
                        .isTrue();
                context.publishEvent(new ContextRefreshedEvent(context.getSourceApplicationContext()));
            }
            assertThat(events.list).hasSize(1);
        });
        assertThat(events.list).hasSize(1);
    }

    @Test
    void resolvingAndEvaluatingConditionsWithoutStartupNeverLogs() {
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(new MockEnvironment());
        when(context.getClassLoader()).thenReturn(getClass().getClassLoader());
        for (int i = 0; i < 3; i++) {
            assertThat(BootUiActivationCondition.resolve(context.getEnvironment(), context.getClassLoader())
                            .enabled())
                    .isFalse();
            assertThat(new BootUiActivationCondition().matches(context, null)).isFalse();
            assertThat(new BootUiInactiveCondition().matches(context, null)).isTrue();
        }
        assertThat(events.list).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"OFF", "false", "no", " off "})
    void explicitOffOnlyLogsAtDebug(String mode) {
        runner().withPropertyValues("bootui.enabled=" + mode, "spring.profiles.active=dev")
                .run(context -> assertSingleDiagnostic(Level.DEBUG, "BootUI is inactive: bootui.enabled=OFF."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production", "private-profile-secret"})
    void disabledProfileOnlyLogsAtDebugWithoutEchoingTheProfile(String profile) {
        runner().withPropertyValues("spring.profiles.active=" + profile + ",dev", "bootui.disabled-profiles=" + profile)
                .run(context -> assertSingleDiagnostic(
                        Level.DEBUG, "BootUI is inactive: an active profile matches bootui.disabled-profiles."));
    }

    @ParameterizedTest
    @CsvSource({"OFF,dev", "AUTO,prod", "AUTO,production"})
    void deliberateDisablementIsSilentAtInfo(String mode, String profile) {
        logger.setLevel(Level.INFO);
        runner().withPropertyValues("bootui.enabled=" + mode, "spring.profiles.active=" + profile)
                .run(context -> assertThat(events.list).isEmpty());
    }

    @Test
    void invalidConfigurationStaysClosedAndLogsOnlyAFixedSafeWarning() {
        runner().withPropertyValues(
                        "bootui.enabled=secret-token\nforged-log-line" + "x".repeat(4_096),
                        "spring.profiles.active=private-profile-secret,dev",
                        "bootui.disabled-profiles=private-profile-secret")
                .run(context -> {
                    assertThat(context).hasBean("bootUiInactiveStartupDiagnostic");
                    assertThat(BootUiActivationCondition.resolve(context.getEnvironment(), context.getClassLoader())
                                    .inactivity())
                            .isEqualTo(BootUiActivation.Inactivity.INVALID_CONFIGURATION);
                    assertSingleDiagnostic(
                            Level.WARN,
                            "BootUI is inactive: invalid bootui.enabled configuration (fail-closed). Use AUTO, ON, or OFF.");
                });
    }

    @Test
    void ordinaryAutoDiagnosticDoesNotEchoCustomProfilesOrPaths() {
        runner().withPropertyValues(
                        "spring.profiles.active=private-active-profile",
                        "bootui.enabled-profiles=private-enabled-profile",
                        "bootui.disabled-profiles=private-disabled-profile",
                        "bootui.path=/private-path")
                .run(context -> {
                    assertThat(events.list).singleElement().satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.INFO);
                        assertThat(event.getFormattedMessage())
                                .startsWith("BootUI is inactive in AUTO mode:")
                                .doesNotContain("private-", "\n", "\r")
                                .hasSizeLessThan(300);
                    });
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ON", "true", "yes"})
    void enabledInstallationDoesNotRegisterAnInactiveDiagnostic(String mode) {
        runner().withPropertyValues("bootui.enabled=" + mode, "spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("bootUiInactiveStartupDiagnostic");
                    assertThat(events.list).isEmpty();
                });
    }

    @Test
    void enabledProfileDoesNotRegisterAnInactiveDiagnostic() {
        runner().withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).doesNotHaveBean("bootUiInactiveStartupDiagnostic");
            assertThat(events.list).isEmpty();
        });
    }

    @Test
    void diagnosticDoesNotRequireThePackagedShell() {
        runner().withClassLoader(new FilteredClassLoader(new ClassPathResource("META-INF/resources/bootui/index.html")))
                .run(context -> {
                    assertThat(context)
                            .hasBean("bootUiInactiveStartupDiagnostic")
                            .doesNotHaveBean("bootUiShellGuardFilter")
                            .doesNotHaveBean("bootUiReactiveShellGuardFilter");
                    assertThat(events.list)
                            .singleElement()
                            .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.INFO));
                });
    }

    @Test
    void diagnosticDoesNotRequireAnEngineOrEitherWebStack() {
        runner().withClassLoader(new FilteredClassLoader(
                        "io.github.jdubois.bootui.engine", "jakarta.servlet", "org.springframework.web"))
                .run(context -> {
                    assertThat(context)
                            .hasBean("bootUiInactiveStartupDiagnostic")
                            .doesNotHaveBean("bootUiShellGuardFilter")
                            .doesNotHaveBean("bootUiReactiveShellGuardFilter");
                    assertThat(events.list)
                            .singleElement()
                            .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.INFO));
                });
    }

    @Test
    void eachWebStackEmitsJustOneStartupDiagnostic() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiShellGuardAutoConfiguration.class))
                .run(context -> assertThat(events.list).hasSize(1));
        events.list.clear();
        new ReactiveWebApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader("jakarta.servlet"))
                .withConfiguration(AutoConfigurations.of(BootUiShellGuardAutoConfiguration.class))
                .run(context -> assertThat(events.list).hasSize(1));
    }

    private void assertSingleDiagnostic(Level level, String message) {
        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(level);
            assertThat(event.getFormattedMessage()).isEqualTo(message);
        });
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiShellGuardAutoConfiguration.class));
    }
}
