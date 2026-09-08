package io.github.jdubois.bootui.autoconfigure.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.sample.BootUiSampleApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

/** Native sample wiring, including Boot observability, cache activity wrapping and MVC registrations. */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "bootui.enabled=ON",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/bootui-spring-wiring-overrides.properties"
        })
class SpringScanWiringTest {
    @Autowired
    ConfigurableApplicationContext application;

    @Autowired
    SpringController controller;

    @Test
    void standardSampleRegistrationsDoNotCreateFalseEvidenceGaps() {
        var factory = application.getBeanFactory();
        assertThat(factory.getSingleton("observabilitySchedulingConfigurer")
                        .getClass()
                        .getName())
                .isEqualTo(
                        "org.springframework.boot.micrometer.observation.autoconfigure.ScheduledTasksObservationAutoConfiguration$ObservabilitySchedulingConfigurer");
        assertThat(factory.getSingleton("cacheManager").getClass().getName())
                .isEqualTo("io.github.jdubois.bootui.autoconfigure.cache.CacheActivityCacheManager");
        var snapshot = SpringInventory.discover(factory, application.getEnvironment(), false);
        assertThat(snapshot.observations().get(SpringObservations.Fact.SCHEDULED_TASK_COUNT, Integer.class))
                .isOne();
        assertThat(snapshot.cacheManagers())
                .anySatisfy(ref -> assertThat(ref.className())
                        .isEqualTo("org.springframework.cache.caffeine.CaffeineCacheManager"));
        var osiv = snapshot.observations().get(SpringObservations.Fact.OSIV, SpringObservations.OsivObservation.class);
        assertThat(osiv.complete()).isTrue();
        assertThat(osiv.registration()).isNull();
        for (SpringRule rule : java.util.List.of(
                new SchedulerPoolTooSmallRule(), new InMemoryCacheManagerRule(), new OpenSessionInViewEnabledRule())) {
            var result = rule.evaluate(snapshot);
            assertThat(result.status())
                    .as(result.id() + " " + result.sampleViolations())
                    .isEqualTo("PASS");
        }
        assertThat(snapshot.observations()
                        .evaluation()
                        .evidence(java.util.List.of())
                        .coverageComplete())
                .isTrue();
        assertThat(snapshot.observations()
                        .evaluation()
                        .evidence(java.util.List.of())
                        .usable())
                .isTrue();

        var report = controller.scan();
        assertThat(report.rulesEvaluated()).isEqualTo(38);
        assertThat(report.evidence().coverageComplete()).isTrue();
        assertThat(report.evidence().limitations()).isEmpty();
        assertThat(report.analysisErrors()).isEmpty();
        assertThat(report.results())
                .noneMatch(result -> java.util.Set.of("SPRING-PERF-005", "SPRING-CACHE-001", "SPRING-JPA-001")
                        .contains(result.id()));
    }
}
