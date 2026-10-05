package io.github.jdubois.bootui.autoconfigure.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.engine.advisor.AdvisorViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class SpringViolationDetailsTests {
    private static final String RULE = "SPRING-WIRING-009";
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    private static SpringScanner scanner(List<String> details) {
        return new SpringScanner(
                SpringContext.builder(new MockEnvironment().withProperty("spring.application.name", "test"))
                        .beanDefinitionCount(40)
                        .mutableSingletonFields(details)
                        .observations(new SpringObservations(Map.of(), List.of()))
                        .build(),
                CLOCK);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 11, 29})
    void retainsExactCountedSequenceWhileKeepingTenSamples(int count) {
        List<String> details = IntStream.range(0, count)
                .mapToObj(i -> "example.Bean.field" + i)
                .toList();
        SpringScanner scanner = scanner(details);
        assertThat(scanner.lastReport().violationDetails().scanId()).isNull();

        var report = scanner.scan();
        var finding = report.results().stream()
                .filter(result -> result.id().equals(RULE))
                .findFirst()
                .orElseThrow();
        assertThat(finding.violationCount()).isEqualTo(count);
        assertThat(finding.sampleViolations()).containsExactlyElementsOf(details.subList(0, Math.min(10, count)));
        var first = scanner.ruleViolations(RULE, report.violationDetails().scanId(), 0, 10);
        var second = scanner.ruleViolations(RULE, report.violationDetails().scanId(), 10, 100);
        assertThat(java.util.stream.Stream.concat(first.violations().stream(), second.violations().stream())
                        .toList())
                .containsExactlyElementsOf(details);
        assertThat(second.page().hasMore()).isFalse();
        assertThat(first.truncated()).isFalse();
        assertThat(scanner.lastReport()).isSameAs(report);
        assertThat(scanner.applyDismissals(report, Set.of(RULE)).violationDetails())
                .isEqualTo(report.violationDetails());
    }

    @Test
    void freezesLiveRetentionPerScanAndKeepsDismissedDetails() {
        List<String> details = IntStream.range(0, 29).mapToObj(i -> "field" + i).toList();
        SpringScanner scanner = scanner(details);
        AtomicInteger retention = new AtomicInteger(12);
        scanner.setViolationRetentionLimit(retention::get);
        var first = scanner.scan();
        String scanId = first.violationDetails().scanId();
        retention.set(40);
        var page = scanner.ruleViolations(RULE, scanId, null, null);
        assertThat(page.violationCount()).isEqualTo(29);
        assertThat(page.violations()).containsExactlyElementsOf(details.subList(0, 12));
        assertThat(page.truncated()).isTrue();
        assertThat(first.violationDetails().retentionLimit()).isEqualTo(12);
        assertThat(scanner.applyDismissals(first, Set.of(RULE)).results()).anySatisfy(result -> {
            assertThat(result.id()).isEqualTo(RULE);
            assertThat(result.dismissed()).isTrue();
        });
        assertThat(scanner.ruleViolations(RULE, scanId, null, null)).isEqualTo(page);
        var second = scanner.scan();
        assertThat(second.violationDetails().scanId()).isNotEqualTo(scanId);
        assertThat(scanner.ruleViolations(RULE, second.violationDetails().scanId(), null, null)
                        .violations())
                .containsExactlyElementsOf(details);
        assertThatThrownBy(() -> scanner.ruleViolations(RULE, scanId, 0, 100))
                .isInstanceOf(AdvisorViolationException.class);
    }

    @Test
    void usesTheExistingDetailSanitizerWithoutDeduplicating() {
        String raw = "field\n" + "x".repeat(300);
        SpringScanner scanner = scanner(java.util.Collections.nCopies(11, raw));
        var report = scanner.scan();
        var page = scanner.ruleViolations(RULE, report.violationDetails().scanId(), null, null);
        assertThat(page.violations())
                .containsExactlyElementsOf(java.util.Collections.nCopies(11, SpringRuleSupport.detail(raw)));
    }
}
