package io.github.jdubois.bootui.core.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MySqlDtoTests {
    @Test
    void reportOwnsItsCollectionsAndKeepsUnknownTimestamps() {
        List<String> limitations = new ArrayList<>(List.of("partial"));
        MySqlInsightReport report = new MySqlInsightReport(
                true, "bounded", "NOT_READ", null, null, null, 0, null, null, limitations, false);
        limitations.clear();
        assertThat(report.limitations()).containsExactly("partial");
        assertThat(report.dataSources()).isEmpty();
        assertThat(report.diagnostics()).isEmpty();
        assertThat(report.readAt()).isNull();
        assertThatThrownBy(() -> report.limitations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void allDatasourceListsAreImmutableDefensiveCopies() throws Exception {
        var constructor = MySqlDataSourceDto.class.getDeclaredConstructors()[0];
        Object[] arguments = new Object[constructor.getParameterCount()];
        List<Object> mutable = new ArrayList<>();
        for (int index = 0; index < arguments.length; index++) {
            Class<?> type = constructor.getParameterTypes()[index];
            arguments[index] = type == boolean.class ? false : type == List.class ? mutable : null;
        }
        MySqlDataSourceDto source = (MySqlDataSourceDto) constructor.newInstance(arguments);
        mutable.add("must not leak");
        for (var component : MySqlDataSourceDto.class.getRecordComponents()) {
            if (component.getType() == List.class) {
                List<?> list = (List<?>) component.getAccessor().invoke(source);
                assertThat(list).isEmpty();
                assertThatThrownBy(() -> ((List<Object>) list).add("mutation"))
                        .isInstanceOf(UnsupportedOperationException.class);
            }
        }
    }

    @Test
    void exactUnsignedCountersAndUnknownTimingRemainDistinctFromObservedZero() {
        MySqlStatementDto statement = new MySqlStatementDto(
                "digest", "schema", "SELECT ?", "18446744073709551615", null, null, 0.0, "0", null, "0", "0", "0");
        assertThat(statement.calls()).isEqualTo("18446744073709551615");
        assertThat(statement.totalTimeMs()).isNull();
        assertThat(statement.maxTimeMs()).isZero();
        assertThat(statement.rowsExamined()).isEqualTo("0");
        assertThat(statement.rowsSent()).isNull();
    }
}
