package io.github.jdubois.bootui.core.dto;

/**
 * Availability status for one BootUI sidebar panel.
 */
public record PanelDto(
        String id,
        String title,
        boolean available,
        String unavailableReason,
        boolean enabled,
        boolean readOnly,
        String readOnlyReason) {

    public PanelDto(String id, String title, boolean available, String unavailableReason) {
        this(id, title, available, unavailableReason, true, false, null);
    }
}
