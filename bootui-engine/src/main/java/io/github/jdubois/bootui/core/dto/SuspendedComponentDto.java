package io.github.jdubois.bootui.core.dto;

/**
 * One BootUI component that low-footprint mode tried to pause.
 *
 * @param id stable kebab-case component id, such as {@code runtime-journal}
 * @param label human-readable component name
 * @param suspended whether the component is paused
 * @param failure why the component could not be paused or resumed, or {@code null} when it could
 */
public record SuspendedComponentDto(String id, String label, boolean suspended, String failure) {}
