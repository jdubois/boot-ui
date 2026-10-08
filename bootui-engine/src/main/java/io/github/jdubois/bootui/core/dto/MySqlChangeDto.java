package io.github.jdubois.bootui.core.dto;

/** Reset-sensitive, same-server counter delta across this metric's own observation interval. */
public record MySqlChangeDto(
        String metric,
        String scope,
        String unit,
        String delta,
        long previousReadAt,
        long readAt,
        String qualification) {}
