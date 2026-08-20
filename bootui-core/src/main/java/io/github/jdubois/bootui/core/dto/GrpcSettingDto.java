package io.github.jdubois.bootui.core.dto;

/**
 * One bounded, framework-exposed gRPC setting rendered as a name/value pair.
 *
 * <p>Used for keepalive and other transport settings whose exact key set differs between Spring gRPC and
 * Quarkus gRPC. Modelling them as neutral pairs keeps the shared DTO stable while letting each adapter
 * report the settings its framework actually exposes, instead of inventing values for the other stack.</p>
 *
 * @param name display name of the setting, already normalized by the adapter
 * @param value display value, already routed through the exposure/masking policy
 */
public record GrpcSettingDto(String name, String value) {}
