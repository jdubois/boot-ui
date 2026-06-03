package io.github.jdubois.bootui.core.dto;

/**
 * A host/container port mapping exposed by a local development service.
 */
public record DevServicePortDto(Integer containerPort, Integer hostPort, String protocol) {}
