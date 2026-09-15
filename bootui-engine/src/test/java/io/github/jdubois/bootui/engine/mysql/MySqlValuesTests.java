package io.github.jdubois.bootui.engine.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import org.junit.jupiter.api.Test;

class MySqlValuesTests {
    @Test
    void preservesUnsignedPrecisionAndPicosecondUnits() {
        assertThat(MySqlValues.counter("18446744073709551615")).isEqualTo("18446744073709551615");
        assertThat(MySqlValues.counter("-1")).isNull();
        assertThat(MySqlValues.counter("unknown")).isNull();
        assertThat(MySqlValues.counter(null)).isNull();
        assertThat(MySqlValues.millis("1500000000", true)).isEqualTo(1.5);
        assertThat(MySqlValues.millis("0", true)).isZero();
        assertThat(MySqlValues.millis("0", false)).isNull();
        assertThat(MySqlValues.number("1e1000")).isNull();
    }

    @Test
    void normalizedDigestExposureAndStringsAreBounded() {
        assertThat(MySqlValues.digest("SELECT ?", new MySqlValues.Policy(ValueExposure.METADATA_ONLY, true)))
                .isEqualTo(SecretMasker.MASKED_VALUE);
        assertThat(MySqlValues.exposed("password", "fixture", new MySqlValues.Policy(ValueExposure.MASKED, true)))
                .isEqualTo(SecretMasker.MASKED_VALUE);
        assertThat(MySqlValues.text("a".repeat(1000))).hasSize(400);
        assertThat(MySqlValues.text("hello\nworld")).isEqualTo("hello world");
        assertThat(MySqlValues.text(null)).isNull();
    }
}
