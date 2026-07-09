package io.github.jdubois.bootui.engine.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.junit.jupiter.api.Test;

/**
 * Verifies the shared blank-string and ISO-instant query-parameter helpers reused across the engine and
 * both adapters (notably the Security Logs, Live Activity, and Cache panels).
 */
class BlankStringsTests {

    @Test
    void blankToNullReturnsNullForNullAndBlank() {
        assertThat(BlankStrings.blankToNull(null)).isNull();
        assertThat(BlankStrings.blankToNull("")).isNull();
        assertThat(BlankStrings.blankToNull("   ")).isNull();
    }

    @Test
    void blankToNullPreservesSurroundingWhitespaceOnNonBlankValues() {
        assertThat(BlankStrings.blankToNull("  value  ")).isEqualTo("  value  ");
    }

    @Test
    void blankToNullTrimmedReturnsNullForNullAndBlank() {
        assertThat(BlankStrings.blankToNullTrimmed(null)).isNull();
        assertThat(BlankStrings.blankToNullTrimmed("")).isNull();
        assertThat(BlankStrings.blankToNullTrimmed("   ")).isNull();
    }

    @Test
    void blankToNullTrimmedTrimsNonBlankValues() {
        assertThat(BlankStrings.blankToNullTrimmed("  value  ")).isEqualTo("value");
    }

    @Test
    void parseInstantReturnsNullForNullAndBlank() {
        assertThat(BlankStrings.parseInstant(null)).isNull();
        assertThat(BlankStrings.parseInstant("")).isNull();
        assertThat(BlankStrings.parseInstant("   ")).isNull();
    }

    @Test
    void parseInstantParsesATrimmedIsoInstant() {
        assertThat(BlankStrings.parseInstant("  2024-01-15T10:30:00Z  "))
                .isEqualTo(Instant.parse("2024-01-15T10:30:00Z"));
    }

    @Test
    void parseInstantPropagatesDateTimeParseExceptionForMalformedInput() {
        assertThatThrownBy(() -> BlankStrings.parseInstant("not-an-instant"))
                .isInstanceOf(DateTimeParseException.class);
    }
}
