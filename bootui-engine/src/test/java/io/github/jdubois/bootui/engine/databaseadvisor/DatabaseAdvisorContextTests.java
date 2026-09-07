package io.github.jdubois.bootui.engine.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DatabaseAdvisorContextTests {

    @Test
    void keepsUnknownCoverageBoundedAndSeparateFromEvidence() {
        DatabaseAdvisorContext context = new DatabaseAdvisorContext(List.of(), false, List.of());
        for (int index = 0; index < 500; index++) {
            context.unknown("DB-SCHEMA-001", "Unknown primary key for table " + index);
        }

        assertThat(context.evaluationDiagnostics()).hasSize(101);
        assertThat(context.evaluationDiagnostics().get(100).message()).contains("omitted");
        assertThat(context.evaluationDiagnostics())
                .allSatisfy(diagnostic -> assertThat(diagnostic.level()).isEqualTo("WARNING"));
        assertThat(context.schemas()).isEmpty();
        assertThatThrownBy(() -> context.evaluationDiagnostics().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void repeatedUnknownCoverageIsNotDuplicated() {
        DatabaseAdvisorContext context = new DatabaseAdvisorContext(List.of(), false, List.of());
        context.unknown("DB-SCHEMA-001", "Primary key metadata was truncated");
        context.unknown("DB-SCHEMA-001", "Primary key metadata was truncated");

        assertThat(context.evaluationDiagnostics()).hasSize(1);
    }
}
