package io.github.jdubois.bootui.autoconfigure.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.BeanRef;
import io.github.jdubois.bootui.core.dto.SpringReport;
import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;
import io.github.jdubois.bootui.core.dto.SpringSeverityCountDto;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SpringScannerTests {

    private static final int RULE_COUNT = 31;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-06T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void initialReportIsNotScanned() {
        SpringContext context = cleanContext(new MockEnvironment());
        SpringScanner scanner = new SpringScanner(context, CLOCK);

        SpringReport report = scanner.initialReport();

        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.violationsFound()).isZero();
        assertThat(report.results()).isEmpty();
        assertThat(report.scan().scannedAt()).isNull();
    }

    @Test
    void scanReportsFindingsAcrossCategories() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.main.allow-bean-definition-overriding", "true")
                .withProperty("spring.main.allow-circular-references", "true")
                .withProperty("debug", "true")
                .withProperty("server.shutdown", "immediate");
        // No active profile, DevTools on the classpath, virtual threads unsupported.
        SpringContext context = SpringContext.builder(environment)
                .virtualThreadsSupported(false)
                .beanDefinitionCount(120)
                .objectMappers(List.of(new BeanRef("objectMapper", false)))
                .dataSources(List.of(new BeanRef("dataSource", false)))
                .devToolsPresent(true)
                .build();
        SpringScanner scanner = new SpringScanner(context, CLOCK);

        SpringReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.componentsAnalyzed()).isEqualTo(120);
        assertThat(report.rulesEvaluated()).isEqualTo(RULE_COUNT);
        assertThat(report.violationsFound())
                .isPositive()
                .isEqualTo(report.results().size());
        assertThat(report.results())
                .extracting(SpringRuleResultDto::id)
                .contains(
                        "SPRING-WIRING-001",
                        "SPRING-WIRING-002",
                        "SPRING-CONFIG-002",
                        "SPRING-PROFILE-001",
                        "SPRING-PROFILE-002",
                        "SPRING-WEB-001",
                        "SPRING-WEB-002",
                        "SPRING-WEB-003");
        // Every reported result is a violation, and severity counts add up to the total.
        assertThat(report.results())
                .allSatisfy(result -> assertThat(result.status()).isEqualTo("VIOLATION"));
        assertThat(report.severityCounts().stream()
                        .mapToInt(SpringSeverityCountDto::count)
                        .sum())
                .isEqualTo(report.violationsFound());
        assertThat(report.inspected()).isNotEmpty();
    }

    @Test
    void scanReportsNoFindingsForAWellConfiguredContext() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.withProperty("server.compression.enabled", "true");
        environment.withProperty("server.shutdown", "graceful");
        environment.withProperty("server.http2.enabled", "true");
        environment.withProperty("spring.application.name", "sample");
        environment.withProperty("server.forward-headers-strategy", "framework");
        SpringContext context = cleanContext(environment);
        SpringScanner scanner = new SpringScanner(context, CLOCK);

        SpringReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.rulesEvaluated()).isEqualTo(RULE_COUNT);
        assertThat(report.violationsFound()).isZero();
        assertThat(report.results()).isEmpty();
        assertThat(report.severityCounts())
                .allSatisfy(count -> assertThat(count.count()).isZero());
    }

    private static SpringContext cleanContext(MockEnvironment environment) {
        return SpringContext.builder(environment)
                .virtualThreadsSupported(false)
                .beanDefinitionCount(100)
                .objectMappers(List.of(new BeanRef("objectMapper", false)))
                .dataSources(List.of(new BeanRef("dataSource", false)))
                .build();
    }
}
