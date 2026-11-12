package io.github.jdubois.bootui.engine.staticsecurity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SpotBugsJavaCompatibilityTests {

    // Sourced matrix (ASM release notes + spotbugs core POMs, 2026-11-12):
    //   spotbugs core 4.8.6 -> ASM 9.7 (max Java 23); 4.9.0-4.9.3 -> ASM 9.7.1 (max Java 24);
    //   4.9.4+ -> ASM 9.8 (Java 25). spotbugs-maven-plugin "a.b.c.d" bundles spotbugs core "a.b.c".
    @Test
    void plugin_4_9_3_0_cannot_read_java_25() {
        Optional<SpotBugsJavaCompatibility.Result> r = SpotBugsJavaCompatibility.evaluate("4.9.3.0", 25);
        assertThat(r).isPresent();
        assertThat(r.get().declaredCore()).isEqualTo("4.9.3");
        assertThat(r.get().minimumCore()).isEqualTo("4.9.4");
    }

    @Test
    void plugin_4_9_4_0_reads_java_25() {
        assertThat(SpotBugsJavaCompatibility.evaluate("4.9.4.0", 25)).isEmpty();
    }

    @Test
    void plugin_4_10_5_0_reads_java_25() {
        assertThat(SpotBugsJavaCompatibility.evaluate("4.10.5.0", 25)).isEmpty();
    }

    @Test
    void plugin_4_8_6_0_cannot_read_java_24_or_25() {
        assertThat(SpotBugsJavaCompatibility.evaluate("4.8.6.0", 24)).isPresent();
        assertThat(SpotBugsJavaCompatibility.evaluate("4.8.6.0", 24).get().minimumCore())
                .isEqualTo("4.9.0");
        assertThat(SpotBugsJavaCompatibility.evaluate("4.8.6.0", 25)).isPresent();
    }

    @Test
    void java_23_and_below_never_warns() {
        assertThat(SpotBugsJavaCompatibility.evaluate("4.8.6.0", 23)).isEmpty();
        assertThat(SpotBugsJavaCompatibility.evaluate("4.0.0.0", 17)).isEmpty();
    }

    @Test
    void java_26_and_above_is_unknown_so_never_warns() {
        assertThat(SpotBugsJavaCompatibility.evaluate("4.9.4.0", 26)).isEmpty();
    }

    @Test
    void unparseable_or_property_version_is_skipped() {
        assertThat(SpotBugsJavaCompatibility.evaluate("${spotbugs.version}", 25))
                .isEmpty();
        assertThat(SpotBugsJavaCompatibility.evaluate("", 25)).isEmpty();
        assertThat(SpotBugsJavaCompatibility.evaluate("latest.release", 25)).isEmpty();
    }

    @Test
    void unknown_java_release_is_skipped() {
        assertThat(SpotBugsJavaCompatibility.evaluate("4.8.6.0", 0)).isEmpty();
    }
}
