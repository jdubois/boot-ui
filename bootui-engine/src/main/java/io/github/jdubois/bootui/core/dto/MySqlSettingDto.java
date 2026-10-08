package io.github.jdubois.bootui.core.dto;

/** A fixed allow-listed setting with GLOBAL provenance, not BootUI's modified inspection-session values. */
public record MySqlSettingDto(String name, String value, String scope, String source) {}
