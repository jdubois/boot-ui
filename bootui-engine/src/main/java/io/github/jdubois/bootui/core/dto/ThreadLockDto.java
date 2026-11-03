package io.github.jdubois.bootui.core.dto;

/** A captured ownership observation; its identity is not stable across captures. */
public record ThreadLockDto(String kind, String identity, Integer acquisitionDepth) {}
