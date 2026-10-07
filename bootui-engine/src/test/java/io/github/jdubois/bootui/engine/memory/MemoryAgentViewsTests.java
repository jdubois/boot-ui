package io.github.jdubois.bootui.engine.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.LiveMemoryReport;
import org.junit.jupiter.api.Test;

class MemoryAgentViewsTests {

    @Test
    void liveMemoryAndJvmTuningEachReturnTheirOwnPanelsPart() {
        LiveMemoryReport report = new MemoryReportProvider().buildReport(null, null, null, null, null);
        assertThat(report.pools()).isNotEmpty();
        assertThat(report.calculation()).isNotNull();

        LiveMemoryReport memory = MemoryAgentViews.liveMemory(report);
        LiveMemoryReport tuning = MemoryAgentViews.jvmTuning(report);

        assertThat(memory.heap()).isEqualTo(report.heap());
        assertThat(memory.pools()).isEqualTo(report.pools());
        assertThat(memory.jvmInputArguments()).isEmpty();
        assertThat(memory.suggestedJvmOptions()).isNull();
        assertThat(memory.calculation()).isNull();
        assertThat(memory.kubernetes()).isNull();

        assertThat(tuning.pools()).isEmpty();
        assertThat(tuning.calculation()).isEqualTo(report.calculation());
        assertThat(tuning.suggestedJvmOptions()).isEqualTo(report.suggestedJvmOptions());
        assertThat(tuning.kubernetes()).isEqualTo(report.kubernetes());
        assertThat(tuning.jvmInputArguments()).isEqualTo(report.jvmInputArguments());
        assertThat(memory).isNotEqualTo(tuning);
        assertThat(MemoryAgentViews.liveMemory(null)).isNull();
    }
}
