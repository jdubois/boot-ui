package io.github.jdubois.bootui.quarkus.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.logging.Level;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the dependency-free level-mapping logic in {@link QuarkusLoggerProvider}. The
 * enumeration / set paths (which need an active JBoss LogManager) are exercised end-to-end by
 * {@code BootUiQuarkusLoggersResourceIT}; here the {@code intValue}-bucketing read mapping and the
 * canonical-name-to-{@link Level} write mapping are pinned directly (they run without a backend), since
 * they are the part most likely to silently drift.
 */
class QuarkusLoggerProviderTest {

    @Test
    void exposesTheCanonicalLevelVocabularyInDescendingSeverity() {
        assertThat(QuarkusLoggerProvider.LEVELS)
                .containsExactly("OFF", "FATAL", "ERROR", "WARN", "INFO", "DEBUG", "TRACE");
    }

    @Test
    void mapsStandardJulLevelsOntoCanonicalNamesBySeverity() {
        assertThat(QuarkusLoggerProvider.canonicalName(Level.OFF)).isEqualTo("OFF");
        assertThat(QuarkusLoggerProvider.canonicalName(Level.SEVERE)).isEqualTo("ERROR");
        assertThat(QuarkusLoggerProvider.canonicalName(Level.WARNING)).isEqualTo("WARN");
        assertThat(QuarkusLoggerProvider.canonicalName(Level.INFO)).isEqualTo("INFO");
        assertThat(QuarkusLoggerProvider.canonicalName(Level.CONFIG)).isEqualTo("DEBUG");
        assertThat(QuarkusLoggerProvider.canonicalName(Level.FINE)).isEqualTo("DEBUG");
        assertThat(QuarkusLoggerProvider.canonicalName(Level.FINER)).isEqualTo("TRACE");
        assertThat(QuarkusLoggerProvider.canonicalName(Level.FINEST)).isEqualTo("TRACE");
        assertThat(QuarkusLoggerProvider.canonicalName(Level.ALL)).isEqualTo("TRACE");
    }

    @Test
    void mapsNullLevelToNull() {
        assertThat(QuarkusLoggerProvider.canonicalName(null)).isNull();
    }

    @Test
    void everyCanonicalNameRoundTripsThroughAJulLevel() {
        for (String level : QuarkusLoggerProvider.LEVELS) {
            assertThat(QuarkusLoggerProvider.canonicalName(QuarkusLoggerProvider.toJulLevel(level)))
                    .as("round-trip for %s", level)
                    .isEqualTo(level);
        }
    }

    @Test
    void parsesCanonicalNamesCaseAndWhitespaceInsensitively() {
        assertThat(QuarkusLoggerProvider.canonicalName(QuarkusLoggerProvider.toJulLevel("debug")))
                .isEqualTo("DEBUG");
        assertThat(QuarkusLoggerProvider.canonicalName(QuarkusLoggerProvider.toJulLevel("  warn  ")))
                .isEqualTo("WARN");
    }

    @Test
    void treatsNullOrBlankLevelAsAReset() {
        assertThat(QuarkusLoggerProvider.toJulLevel(null)).isNull();
        assertThat(QuarkusLoggerProvider.toJulLevel("")).isNull();
        assertThat(QuarkusLoggerProvider.toJulLevel("   ")).isNull();
    }

    @Test
    void rejectsAnUnknownLevelName() {
        assertThatThrownBy(() -> QuarkusLoggerProvider.toJulLevel("LOUD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LOUD");
    }
}
