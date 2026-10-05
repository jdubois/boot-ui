package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Pins the derivation of every prefix under which Spring can serve the packaged BootUI bundle, which is
 * what keeps the shell guard from being defeated by a host's static-resource configuration (#856).
 */
class BootUiShellGuardMountsTests {

    @Test
    void defaultsToTheReservedMountOnly() {
        assertThat(BootUiShellGuardMounts.servlet(new MockEnvironment())).containsExactly("/bootui");
        assertThat(BootUiShellGuardMounts.reactive(new MockEnvironment())).containsExactly("/bootui");
    }

    @Test
    void addsTheDispatcherServletPathPrefix() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.mvc.servlet.path", "/app");

        assertThat(BootUiShellGuardMounts.servlet(environment)).containsExactly("/bootui", "/app/bootui");
    }

    @Test
    void addsTheStaticPathPatternPrefix() {
        MockEnvironment servlet = new MockEnvironment().withProperty("spring.mvc.static-path-pattern", "/static/**");
        MockEnvironment reactive =
                new MockEnvironment().withProperty("spring.webflux.static-path-pattern", "/static/**");

        assertThat(BootUiShellGuardMounts.servlet(servlet)).containsExactly("/bootui", "/static/bootui");
        assertThat(BootUiShellGuardMounts.reactive(reactive)).containsExactly("/bootui", "/static/bootui");
    }

    @Test
    void combinesTheServletPathAndTheStaticPathPattern() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.mvc.servlet.path", "/app")
                .withProperty("spring.mvc.static-path-pattern", "/static/**");

        assertThat(BootUiShellGuardMounts.servlet(environment)).containsExactly("/bootui", "/app/static/bootui");
    }

    @Test
    void normalizesTrailingSlashesAndMissingLeadingSlashes() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.mvc.servlet.path", "app/")
                .withProperty("spring.mvc.static-path-pattern", "/resources/**");

        assertThat(BootUiShellGuardMounts.servlet(environment)).containsExactly("/bootui", "/app/resources/bootui");
    }

    @Test
    void ignoresPatternsThatCannotReachNestedResources() {
        // "/resources/*" matches exactly one segment, so it can never serve
        // "/resources/bootui/index.html". Claiming that namespace would block host routes for nothing.
        MockEnvironment singleSegment =
                new MockEnvironment().withProperty("spring.mvc.static-path-pattern", "/resources/*");
        MockEnvironment literal = new MockEnvironment().withProperty("spring.mvc.static-path-pattern", "/resources");

        assertThat(BootUiShellGuardMounts.servlet(singleSegment)).containsExactly("/bootui");
        assertThat(BootUiShellGuardMounts.servlet(literal)).containsExactly("/bootui");
    }

    @Test
    void handlesCaptureAllPatterns() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("spring.webflux.static-path-pattern", "/static/{*resource}");

        assertThat(BootUiShellGuardMounts.reactive(environment)).containsExactly("/bootui", "/static/bootui");
    }
}
