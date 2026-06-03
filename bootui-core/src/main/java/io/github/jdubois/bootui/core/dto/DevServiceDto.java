package io.github.jdubois.bootui.core.dto;

import java.util.List;
import java.util.Map;

/**
 * One Docker Compose, Testcontainers, or service-connection entry.
 */
public record DevServiceDto(
        String id,
        String name,
        String type,
        String source,
        String image,
        String status,
        String host,
        List<DevServicePortDto> ports,
        Map<String, Object> connectionDetails,
        boolean restartable,
        boolean logsAvailable,
        String note) {}
