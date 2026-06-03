package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record ScheduledReport(boolean schedulingPresent, int total, List<ScheduledTaskDto> tasks) {}
