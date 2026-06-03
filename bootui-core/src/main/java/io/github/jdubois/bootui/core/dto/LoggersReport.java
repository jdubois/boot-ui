package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record LoggersReport(List<String> availableLevels, List<LoggerDto> loggers, PageMetadata page) {
    public LoggersReport(List<String> availableLevels, List<LoggerDto> loggers) {
        this(
                availableLevels,
                loggers,
                new PageMetadata(loggers.size(), loggers.size(), 0, loggers.size(), loggers.size(), false));
    }
}
