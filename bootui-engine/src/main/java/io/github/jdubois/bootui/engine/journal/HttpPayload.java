package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.resources.ResourceUsage;

/**
 * An HTTP exchange's payload: its method, its decoded path without query string, the route template the framework
 * matched, the named operation it carried (such as a GraphQL {@code query ProductList}), its status, and the CPU time,
 * allocated bytes, and GC pauses its segments measured. The journal resolves its route label from them when it
 * aggregates, so a path is never shown where a template is known.
 *
 * @param resources the request's measured resources ({@code docs/PLAN-v2.md} §5.11), or {@code null} when the
 *     {@code resources} source is off
 */
public record HttpPayload(
        String method, String path, String routeTemplate, String operation, int status, ResourceUsage resources)
        implements RuntimeEventPayload {

    /** An exchange without measured resources. */
    public HttpPayload(String method, String path, String routeTemplate, String operation, int status) {
        this(method, path, routeTemplate, operation, status, null);
    }

    @Override
    public int estimatedBytes() {
        return 16
                + RuntimeEvent.stringBytes(method)
                + RuntimeEvent.stringBytes(path)
                + RuntimeEvent.stringBytes(routeTemplate)
                + RuntimeEvent.stringBytes(operation)
                + (resources == null ? 0 : resources.estimatedBytes());
    }
}
