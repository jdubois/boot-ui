package io.github.jdubois.bootui.autoconfigure.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.security.SecurityModel.PasswordEncoderModel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SecurityViolationDetailsTests {

    @Test
    void nativeSecurityRetainsEveryCountedEncoderIncludingDuplicateSanitizedDetails() {
        List<PasswordEncoderModel> encoders = IntStream.range(0, 29)
                .mapToObj(i -> new PasswordEncoderModel("example.NoOpPasswordEncoder" + i, null))
                .toList();
        SecurityContext context = new SecurityContext(
                List.of(),
                encoders,
                List.of(),
                false,
                List.of(),
                false,
                false,
                false,
                false,
                List.of(),
                false,
                false,
                List.of(),
                false,
                false,
                new MockEnvironment());
        SecurityScanner scanner = new SecurityScanner(context, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        assertThat(scanner.lastReport().violationDetails().scanId()).isNull();
        var report = scanner.scan();
        var rule = report.results().stream()
                .filter(result -> result.id().equals("SEC-AUTH-001"))
                .findFirst()
                .orElseThrow();
        List<String> expected = encoders.stream()
                .map(encoder -> "An active DAO provider selects " + encoder.type() + " for encoding without hashing.")
                .toList();
        assertThat(rule.violationCount()).isEqualTo(29);
        assertThat(rule.sampleViolations()).containsExactlyElementsOf(expected.subList(0, 10));
        var page = scanner.ruleViolations(rule.id(), report.violationDetails().scanId(), null, null);
        assertThat(page.violations()).containsExactlyElementsOf(expected);
        assertThat(page.retainedCount()).isEqualTo(29);
        assertThat(page.truncated()).isFalse();
        assertThat(scanner.lastReport()).isSameAs(report);
        var dismissed = scanner.applyDismissals(report, Set.of(rule.id()));
        assertThat(dismissed.violationDetails()).isEqualTo(report.violationDetails());
        assertThat(scanner.ruleViolations(
                        rule.id(), dismissed.violationDetails().scanId(), null, null))
                .isEqualTo(page);
    }

    @Test
    void nativeSecurityRetentionIsBoundedWithoutChangingSummaryCounts() {
        SecurityContext context = new SecurityContext(
                List.of(),
                java.util.Collections.nCopies(11, new PasswordEncoderModel("example.NoOpPasswordEncoder", null)),
                List.of(),
                false,
                List.of(),
                false,
                false,
                false,
                false,
                List.of(),
                false,
                false,
                List.of(),
                false,
                false,
                new MockEnvironment());
        SecurityScanner scanner = new SecurityScanner(context, Clock.systemUTC());
        scanner.setViolationRetentionLimit(() -> 3);
        var report = scanner.scan();
        var page =
                scanner.ruleViolations("SEC-AUTH-001", report.violationDetails().scanId(), null, null);
        assertThat(page.violationCount()).isEqualTo(11);
        assertThat(page.retainedCount()).isEqualTo(3);
        assertThat(page.violations()).hasSize(3);
        assertThat(page.truncated()).isTrue();
        assertThat(report.results().stream()
                        .filter(result -> result.id().equals("SEC-AUTH-001"))
                        .findFirst()
                        .orElseThrow()
                        .sampleViolations())
                .hasSize(10);
    }
}
