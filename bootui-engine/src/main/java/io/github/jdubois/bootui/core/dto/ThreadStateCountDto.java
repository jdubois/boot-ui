package io.github.jdubois.bootui.core.dto;

/**
 * Number of live threads in a given {@link java.lang.Thread.State}, used for the
 * summary header of the Thread / Process Viewer panel.
 */
public record ThreadStateCountDto(String state, int count) {}
