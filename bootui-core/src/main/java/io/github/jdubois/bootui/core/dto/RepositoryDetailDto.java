package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Detail view of a Spring Data repository, including its query methods.
 */
public record RepositoryDetailDto(
        String beanName,
        String repositoryInterface,
        String domainType,
        String idType,
        String storeModule,
        String customImplementation,
        List<RepositoryMethodDto> methods,
        List<String> fragments) {}
