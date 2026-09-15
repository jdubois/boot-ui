package io.github.jdubois.bootui.core.dto;

/** AVAILABLE with a reason means retained partial evidence; truncation means a BootUI row cap only. */
public record MySqlSectionDto(
        String id,
        String title,
        String status,
        String reason,
        String hint,
        String scope,
        int rowCount,
        boolean truncated) {}
