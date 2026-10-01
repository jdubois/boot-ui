package io.github.jdubois.bootui.engine.journal;

/**
 * An HTTP exchange's payload: its method, its decoded path without query string, the route template the framework
 * matched, the named operation it carried (such as a GraphQL {@code query ProductList}), and its status. The journal
 * resolves its route label from them when it aggregates, so a path is never shown where a template is known.
 */
public record HttpPayload(String method, String path, String routeTemplate, String operation, int status)
        implements RuntimeEventPayload {

    @Override
    public int estimatedBytes() {
        return 16
                + RuntimeEvent.stringBytes(method)
                + RuntimeEvent.stringBytes(path)
                + RuntimeEvent.stringBytes(routeTemplate)
                + RuntimeEvent.stringBytes(operation);
    }
}
