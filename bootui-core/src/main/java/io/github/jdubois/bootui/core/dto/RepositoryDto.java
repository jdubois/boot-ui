package io.github.jdubois.bootui.core.dto;

/**
 * Summary of one Spring Data repository discovered in the context.
 */
public record RepositoryDto(
        String beanName,
        String repositoryInterface,
        String domainType,
        String idType,
        String storeModule,
        String customImplementation,
        int queryMethodCount,
        int fragmentCount) {}
