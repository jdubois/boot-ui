package io.github.jdubois.bootui.engine.codepaths;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which routes' requests executed some methods in this run ({@code docs/PLAN-v2.md} §5.7, §5.17, M5-7a), read from each
 * route's own call trees, never composed from calls observed across requests: a route is listed for a method only when
 * one of its requests' trees executed it, its first request, executor work the agent followed, and late fragments
 * included. The absence of a route proves nothing on its own: see {@link #limitations()} and each route's
 * {@link RouteEvidence}.
 *
 * @param unavailableReason why the code-paths sensor cannot answer this run, or {@code null}
 * @param routesByKey per method key asked for, {@code class#name+descriptor}, the routes whose requests executed it,
 *     with how many of their requests did; a key no request of a known route executed is absent
 * @param unrouted per method key asked for, the request trees without a route, or of a route past the caps, that
 *     executed it
 * @param routes per route with a tree in this run, how complete its evidence is
 * @param excluded the keys asked for that the sensor adaptively excluded in this run: calls to them after that are not
 *     recorded
 * @param beanClasses the classes the sensor instruments in this run, by binary name
 * @param limitations what makes every route's evidence incomplete in this run, such as trees still settling
 */
public record MethodRoutes(
        String unavailableReason,
        Map<String, Map<String, Long>> routesByKey,
        Map<String, Long> unrouted,
        Map<String, RouteEvidence> routes,
        Set<String> excluded,
        Set<String> beanClasses,
        List<String> limitations) {

    public MethodRoutes {
        routesByKey = Map.copyOf(routesByKey);
        unrouted = Map.copyOf(unrouted);
        routes = Map.copyOf(routes);
        excluded = Set.copyOf(excluded);
        beanClasses = Set.copyOf(beanClasses);
        limitations = List.copyOf(limitations);
    }

    /** How a reason starts when the sensor records this run but its trees could not be read. */
    public static final String READ_FAILED = "Code Paths could not be read: ";

    /** Code paths cannot answer, for {@code reason}. */
    public static MethodRoutes unavailable(String reason) {
        return new MethodRoutes(reason, Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), List.of());
    }

    public boolean available() {
        return unavailableReason == null;
    }

    /** Whether the sensor records this run but its trees could not be read. */
    public boolean failed() {
        return unavailableReason != null && unavailableReason.startsWith(READ_FAILED);
    }

    /**
     * One route's evidence.
     *
     * @param requests the requests whose tree was merged into the route's tree, its first included
     * @param partial whether some methods its requests executed may be missing or under-counted
     * @param reasons why, when partial
     */
    public record RouteEvidence(long requests, boolean partial, List<String> reasons) {

        public RouteEvidence {
            reasons = List.copyOf(reasons);
        }
    }
}
