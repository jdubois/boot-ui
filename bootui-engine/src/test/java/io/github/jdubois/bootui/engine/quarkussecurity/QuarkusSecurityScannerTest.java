package io.github.jdubois.bootui.engine.quarkussecurity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import io.github.jdubois.bootui.spi.QuarkusSecurityPermission;
import io.github.jdubois.bootui.spi.QuarkusSecuritySnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuarkusSecurityScannerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);

    private static QuarkusSecuritySnapshot snapshot(
            boolean anyAuth, String insecure, boolean ssl, boolean cors, String origins, boolean creds) {
        return new QuarkusSecuritySnapshot(
                anyAuth,
                false,
                false,
                false,
                false,
                insecure,
                ssl,
                cors,
                origins,
                creds,
                true,
                true,
                false,
                false,
                false,
                false,
                List.of(),
                anyAuth ? 1 : 0,
                0,
                0,
                0,
                0,
                0,
                List.of());
    }

    private static SecurityRuleResultDto find(SecurityReport r, String id) {
        return r.results().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
    }

    @Test
    void emptyAppFlagsNoAuthAndOpenAuthorization() {
        SecurityReport r = QuarkusSecurityScanner.usingSnapshot(
                        () -> snapshot(false, "enabled", false, false, null, false), CLOCK)
                .scan();
        assertThat(find(r, "QS-AUTH-001")).isNotNull();
        assertThat(r.scan().status()).isEqualTo("SCANNED");
        assertThat(r.violationsFound()).isGreaterThan(0);
    }

    @Test
    void wildcardCorsWithCredentialsIsCritical() {
        SecurityReport r = QuarkusSecurityScanner.usingSnapshot(
                        () -> snapshot(true, "redirect", true, true, "*", true), CLOCK)
                .scan();
        assertThat(find(r, "QS-CORS-002").severity()).isEqualTo("CRITICAL");
        assertThat(find(r, "QS-CORS-001")).isNull();
    }

    @Test
    void secureConfigHasNoFindings() {
        QuarkusSecuritySnapshot snap = new QuarkusSecuritySnapshot(
                true,
                true,
                false,
                false,
                false,
                "redirect",
                true,
                true,
                "https://app.example",
                false,
                true,
                true,
                false,
                false,
                false,
                false,
                List.of(new QuarkusSecurityPermission("api", "/api/*", "authenticated")),
                5,
                0,
                0,
                2,
                6,
                6,
                List.of());
        SecurityReport r =
                QuarkusSecurityScanner.usingSnapshot(() -> snap, CLOCK).scan();
        assertThat(r.violationsFound()).isZero();
        assertThat(r.filterChainsAnalyzed()).isEqualTo(1);
    }

    @Test
    void dismissalsHideMatchingFindings() {
        QuarkusSecurityScanner scanner = QuarkusSecurityScanner.usingSnapshot(
                () -> snapshot(false, "enabled", false, false, null, false), CLOCK);
        SecurityReport scanned = scanner.scan();
        int before = scanned.violationsFound();
        SecurityReport after = scanner.applyDismissals(scanned, Set.of("QS-AUTH-001"));
        assertThat(after.violationsFound()).isEqualTo(before - 1);
    }

    @Test
    void initialReportIsNotScanned() {
        SecurityReport r = QuarkusSecurityScanner.usingSnapshot(
                        () -> snapshot(true, "redirect", true, false, null, false), CLOCK)
                .initialReport();
        assertThat(r.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(r.violationsFound()).isZero();
    }
}
