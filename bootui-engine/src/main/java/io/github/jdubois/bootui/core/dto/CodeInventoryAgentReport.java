package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Code Inventory for agents ({@code get_code_inventory}, {@code bootui code inventory}): the summary's counts first,
 * then at most {@code limit} rows of the requested view.
 *
 * @param summary the panel's summary
 * @param view {@code changed}, {@code never-executed}, {@code dependencies}, or {@code package} for a package, class, or
 *     method name
 * @param query the query as given, or {@code null}
 * @param methods the view's methods, for every view but {@code dependencies}
 * @param dependencies the view's dependencies, for {@code dependencies}
 * @param total how many rows the view has
 * @param omitted how many rows were left out past the limit
 */
public record CodeInventoryAgentReport(
        CodeInventoryReport summary,
        String view,
        String query,
        List<CodeInventoryMethodDto> methods,
        List<CodeInventoryDependencyDto> dependencies,
        int total,
        int omitted) {

    /** The rows an agent gets when it asks for no limit. */
    public static final int DEFAULT_LIMIT = 25;

    public CodeInventoryAgentReport {
        methods = DtoCollections.immutableCopy(methods);
        dependencies = DtoCollections.immutableCopy(dependencies);
    }
}
