package io.github.jdubois.bootui.core.dto;

/**
 * One query method on a Spring Data repository.
 */
public record RepositoryMethodDto(
        String name, String signature, String origin, String query, boolean nativeQuery, String namedQuery) {}
