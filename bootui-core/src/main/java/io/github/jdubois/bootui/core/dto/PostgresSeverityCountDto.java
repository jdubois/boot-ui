package io.github.jdubois.bootui.core.dto;

/** Count of PostgreSQL panel findings by normalized severity. */
public record PostgresSeverityCountDto(String severity, int count) {}
