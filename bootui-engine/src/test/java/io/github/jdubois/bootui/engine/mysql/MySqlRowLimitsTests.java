package io.github.jdubois.bootui.engine.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MySqlRowLimitsTests {
    @Test
    void defaultsAndLargestSafeLookaheadAreExact() {
        assertThat(MySqlRowLimits.defaults()).isEqualTo(new MySqlRowLimits(100, 100, 500, 200, 100, 10, 40));
        assertThat(MySqlRowLimits.requireValid("test", Integer.MAX_VALUE - 1)).isEqualTo(Integer.MAX_VALUE - 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void rejectsEveryInvalidBoundWithThePropertyName(int value) {
        assertThatThrownBy(() -> new MySqlRowLimits(1, 1, 1, 1, value, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bootui.mysql.max-lock-waits");
        assertThatThrownBy(() -> MySqlRowLimits.requireValid("bootui.mysql.max-tables", value))
                .hasMessageContaining("bootui.mysql.max-tables");
    }
}
