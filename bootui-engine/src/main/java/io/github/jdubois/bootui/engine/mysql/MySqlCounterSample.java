package io.github.jdubois.bootui.engine.mysql;

import io.github.jdubois.bootui.core.dto.MySqlMetricDto;
import java.util.List;

/** The status query's observation window, independent of later collectors and report completion. */
record MySqlCounterSample(String schemaName, long startedAt, long observedAt, List<MySqlMetricDto> metrics) {
    MySqlCounterSample {
        metrics = List.copyOf(metrics);
    }
}
