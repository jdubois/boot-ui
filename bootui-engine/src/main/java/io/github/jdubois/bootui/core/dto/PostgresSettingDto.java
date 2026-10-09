package io.github.jdubois.bootui.core.dto;

/**
 * One notable {@code pg_settings} row.
 *
 * <p>Only a curated allow-list of operational settings is read, so no credential-bearing or command-bearing
 * setting is ever carried; values are still masked and truncated on the way out.</p>
 *
 * @param source where the value came from ({@code configuration file}, {@code default}, {@code override}, ...)
 * @param note why BootUI shows this setting, or {@code null}
 */
public record PostgresSettingDto(String name, String value, String unit, String source, String note) {}
