package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The application's methods, filtered by package, class, and status: every matching package's counts, the classes of
 * the requested package or class, and a page of the matching methods.
 *
 * @param available whether the inventory sensor records this run
 * @param unavailableReason why not, or {@code null}
 * @param packages the counts of each package with a matching method
 * @param classes the counts of each class with a matching method, when a package or class was requested
 * @param methods a page of the matching methods
 * @param page paging metadata
 */
public record CodeInventoryMethodsReport(
        boolean available,
        String unavailableReason,
        List<CodeInventoryPackageDto> packages,
        List<CodeInventoryClassDto> classes,
        List<CodeInventoryMethodDto> methods,
        PageMetadata page) {

    public CodeInventoryMethodsReport {
        packages = DtoCollections.immutableCopy(packages);
        classes = DtoCollections.immutableCopy(classes);
        methods = DtoCollections.immutableCopy(methods);
    }
}
