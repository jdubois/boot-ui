package io.github.jdubois.bootui.autoconfigure.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.core.NativeDetector;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;

class BootUiCompatibilityListenerTests {

    @Test
    void factoriesDiscoverTheEarlyListenerAndFailureAnalyzer() {
        assertThat(SpringFactoriesLoader.loadFactoryNames(
                        org.springframework.context.ApplicationListener.class,
                        getClass().getClassLoader()))
                .contains(BootUiCompatibilityListener.class.getName());
        assertThat(SpringFactoriesLoader.loadFactoryNames(
                        org.springframework.boot.diagnostics.FailureAnalyzer.class,
                        getClass().getClassLoader()))
                .contains(BootUiCompatibilityFailureAnalyzer.class.getName());
    }

    @Test
    void rejectsMissingBoot4ApiEvenWhenTheConsoleIsOff() {
        SpringApplication application = new SpringApplication(EmptyApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of("bootui.enabled", "OFF", "spring.main.banner-mode", "off"));
        application.setResourceLoader(new DefaultResourceLoader(
                new FilteredClassLoader(name -> name.equals("org.springframework.boot.EnvironmentPostProcessor"))));
        assertThatThrownBy(application::run)
                .isInstanceOf(BootUiCompatibilityException.class)
                .hasMessageContaining("BOOTUI-COMPAT-001")
                .hasMessageContaining("Spring Boot 4");
    }

    @Test
    void failureAnalysisIncludesTheActionInsteadOfAFactoryInstantiationError() {
        var exception = new BootUiCompatibilityException("BOOTUI-COMPAT-001: incompatible", "Upgrade the platform.");
        var analysis = new BootUiCompatibilityFailureAnalyzer().analyze(exception);
        assertThat(analysis.getDescription()).contains("BOOTUI-COMPAT-001");
        assertThat(analysis.getAction()).isEqualTo("Upgrade the platform.");
    }

    @Test
    void nativeStartupDoesNotRepeatBuildTimeClassProbes() {
        var event = mock(ApplicationEnvironmentPreparedEvent.class);
        try (var nativeRuntime = mockStatic(NativeDetector.class)) {
            nativeRuntime.when(NativeDetector::inNativeImage).thenReturn(true);
            new BootUiCompatibilityListener().onApplicationEvent(event);
            verifyNoInteractions(event);
        }
    }

    static class EmptyApplication {}
}
