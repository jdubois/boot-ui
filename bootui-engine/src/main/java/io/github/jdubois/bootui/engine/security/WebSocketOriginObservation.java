package io.github.jdubois.bootui.engine.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Internal, passive declaration facts, not handshake or HTTP CORS evidence. No origin values leave
 * the collector. Unknown policies and incomplete inventory are distinct from confirmed absence.
 */
public record WebSocketOriginObservation(List<Endpoint> endpoints, boolean complete, boolean failed) {
    public static final int MAX_ENDPOINTS = 1000;
    public static final int MAX_ORIGINS = 100;
    public static final int MAX_TEXT = 512;

    public WebSocketOriginObservation {
        complete &= endpoints != null && endpoints.size() <= MAX_ENDPOINTS;
        endpoints = endpoints == null
                ? List.of()
                : List.copyOf(endpoints.subList(0, Math.min(endpoints.size(), MAX_ENDPOINTS)));
    }

    public record Endpoint(String identity, String path, boolean policyKnown, boolean wildcard, boolean unrestricted) {
        public Endpoint(String path, boolean policyKnown, boolean wildcard, boolean unrestricted) {
            this(
                    UUID.nameUUIDFromBytes(String.valueOf(path)
                                    .substring(0, Math.min(String.valueOf(path).length(), MAX_TEXT))
                                    .getBytes(StandardCharsets.UTF_8))
                            .toString(),
                    path,
                    policyKnown,
                    wildcard,
                    unrestricted);
        }

        public Endpoint {
            // Paths may themselves contain credentials, query strings or application data.
            path = path != null && path.length() <= MAX_TEXT && path.matches("/[A-Za-z0-9/_.{}*\\-]*")
                    ? path
                    : "(endpoint path omitted)";
        }
    }

    public static WebSocketOriginObservation unavailable() {
        return new WebSocketOriginObservation(List.of(), false, false);
    }

    public static WebSocketOriginObservation absent() {
        return new WebSocketOriginObservation(List.of(), true, false);
    }

    /** Bounded scan-local builder. Callers must also bound native registry traversal. */
    public static final class Builder {
        private final List<Endpoint> endpoints = new ArrayList<>();
        private boolean complete = true;
        private boolean failed;

        public void incomplete() {
            complete = false;
        }

        public void failed() {
            failed = true;
            complete = false;
        }

        public boolean atCapacity() {
            return endpoints.size() >= MAX_ENDPOINTS;
        }

        public void endpoint(String path, List<String> origins, boolean known) {
            boolean wildcard = false;
            boolean unrestricted = false;
            if (origins == null) {
                known = false;
            } else {
                int count = 0;
                for (String origin : origins) {
                    if (++count > MAX_ORIGINS) {
                        known = false;
                        break;
                    }
                    if (origin == null || origin.length() > MAX_TEXT) {
                        known = false;
                        continue;
                    }
                    wildcard |= origin.indexOf('*') >= 0;
                    unrestricted |= "*".equals(origin);
                }
            }
            if (endpoints.size() >= MAX_ENDPOINTS) {
                incomplete();
                return;
            }
            if (path != null && path.length() > MAX_TEXT) {
                incomplete();
                endpoints.add(new Endpoint("oversize-" + endpoints.size(), null, known, wildcard, unrestricted));
            } else {
                endpoints.add(new Endpoint(path, known, wildcard, unrestricted));
            }
            complete &= known;
        }

        public WebSocketOriginObservation build() {
            return new WebSocketOriginObservation(endpoints, complete, failed);
        }
    }
}
