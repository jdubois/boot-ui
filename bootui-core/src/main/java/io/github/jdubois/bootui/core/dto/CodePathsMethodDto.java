package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Where one method of a tree is called from ({@code docs/PLAN-v2.md} §5.14): its callers within the tree, and the
 * routes whose trees reach it in this run.
 *
 * @param method the method's key, {@code class#name+descriptor}
 * @param callers the methods that call it within the tree, as keys; {@code REQUEST} when the request itself does
 * @param routes the routes whose trees reach it, at most twenty
 */
public record CodePathsMethodDto(String method, List<String> callers, List<String> routes) {

    public CodePathsMethodDto {
        callers = DtoCollections.immutableCopy(callers);
        routes = DtoCollections.immutableCopy(routes);
    }
}
