package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One Spring Boot audit event formatted for the Security Logs panel.
 */
public record SecurityLogEventDto(String timestamp, String principal, String type, List<SecurityLogDataDto> data) {}
