package io.github.jdubois.bootui.autoconfigure.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.sample.BootUiSampleApplication;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.web.SecurityFilterChain;

/** Uses the actual sample chains and embedded Tomcat, not a synthesized rule model or mock servlet context. */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "bootui.enabled=ON",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/bootui-security-wiring-overrides.properties"
        })
class SecurityScanWiringTest {
    @Autowired
    ConfigurableApplicationContext application;

    @Autowired
    SecurityController controller;

    @Test
    void sampleNativeEvidenceIsUsableWithoutClaimingCompleteSecurity() throws Exception {
        var environment = SecurityEnvironmentSnapshot.capture(application.getEnvironment());
        assertThat(environment.getProperty("server.ssl.enabled")).isNull();
        Method method = SecurityScanner.class.getDeclaredMethod("toChainModel", int.class, SecurityFilterChain.class);
        method.setAccessible(true);
        var admin = (SecurityModel.FilterChainModel)
                method.invoke(null, 0, application.getBean("adminSecurity", SecurityFilterChain.class));
        assertThat(admin.details().filtersKnown())
                .as("Native filters: %s", admin.filterNames())
                .isTrue();
        assertThat(admin.details().headersKnown())
                .as("Native headers: %s", admin.headerWriterNames())
                .isTrue();
        assertThat(admin.details().csrfKnown()).isTrue();
        assertThat(admin.permitsAllAnonymous()).isFalse();

        var report = controller.scan();
        assertThat(report.filterChainsAnalyzed()).isEqualTo(3);
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.analysisErrors()).isEmpty();
        assertThat(report.results())
                .extracting(io.github.jdubois.bootui.core.dto.SecurityRuleResultDto::id)
                .doesNotContain("SEC-HEAD-010", "SEC-SESSION-007", "SEC-CONFIG-005", "SEC-ACT-004");
        // The custom filter and custom CSRF request matchers remain unobserved.
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.evidence().limitations()).isNotEmpty();
        assertThat(report.evidence().limitations())
                .noneMatch(reason -> reason.startsWith("SEC-OAUTH")
                        || reason.startsWith("SEC-CONFIG-007")
                        || reason.contains("Actuator operation inventory")
                        || reason.contains("authorization is not a supported constant grant"));
    }
}
