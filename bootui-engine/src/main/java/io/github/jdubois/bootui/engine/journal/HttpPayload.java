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
 * @param timing its monotonic start and phases ({@code docs/PLAN-v2.md} §5.5), or {@code null} when unknown
 */
public record HttpPayload(
        String method,
        String path,
        String routeTemplate,
        String operation,
        int status,
        ResourceUsage resources,
        RequestTiming timing)
        implements RuntimeEventPayload {

    /** An exchange without its timing. */
    public HttpPayload(
            String method, String path, String routeTemplate, String operation, int status, ResourceUsage resources) {
        this(method, path, routeTemplate, operation, status, resources, null);
    }

    /** An exchange without measured resources or timing. */
    public HttpPayload(String method, String path, String routeTemplate, String operation, int status) {
        this(method, path, routeTemplate, operation, status, null);
    }

    /**
     * This exchange with its method, route template, and operation replaced by the run's shared copies. Its path is
     * shared only when it is its own template, since a path with ids would fill the dictionary with one-off strings.
     */
    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        String template = dictionary.shared(routeTemplate);
        return new HttpPayload(
                dictionary.shared(method),
                path != null && path.equals(routeTemplate) ? template : path,
                template,
                dictionary.shared(operation),
                status,
                resources,
                timing);
    }

    /** Its fixed part and its strings, each counted as the payload's own. */
    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /** Its fixed part, with each string {@code dictionary} shares counted as a reference. */
    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return (timing == null ? 16 : 64)
                + JournalDictionary.retained(dictionary, method)
                + JournalDictionary.retained(dictionary, path)
                + JournalDictionary.retained(dictionary, routeTemplate)
                + JournalDictionary.retained(dictionary, operation)
                + (resources == null ? 0 : resources.estimatedBytes());
    }
}
