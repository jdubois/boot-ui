package io.github.jdubois.bootui.engine.quarkussecurity;

import io.github.jdubois.bootui.spi.QuarkusSecurityEndpoint;
import io.github.jdubois.bootui.spi.QuarkusSecurityPermission;
import java.util.ArrayList;
import java.util.List;

/** Supported literal/prefix declarations only; never executes a policy or guesses template expansion. */
final class QuarkusPermissionEvidence {
    enum Decision {
        PUBLIC,
        RESTRICTED,
        UNKNOWN
    }

    private QuarkusPermissionEvidence() {}

    static Decision decision(List<QuarkusSecurityPermission> permissions, QuarkusSecurityEndpoint endpoint) {
        if (endpoint.path() == null
                || endpoint.path().contains("{")
                || endpoint.path().contains("*")
                || endpoint.method() == null) {
            return Decision.UNKNOWN;
        }
        // HTTP and JAX-RS policies run in separate phases; a more specific REST permit cannot bypass HTTP denial.
        Decision http = scopeDecision(permissions, endpoint, "all");
        Decision rest = scopeDecision(permissions, endpoint, "jaxrs");
        if (http == Decision.RESTRICTED || rest == Decision.RESTRICTED) {
            return Decision.RESTRICTED;
        }
        boolean unknownScope = permissions.stream()
                .anyMatch(permission -> !"all".equals(permission.appliesTo())
                        && !"jaxrs".equals(permission.appliesTo())
                        && rank(permission.paths(), endpoint.path()) != -1);
        return unknownScope || http == Decision.UNKNOWN || rest == Decision.UNKNOWN
                ? Decision.UNKNOWN
                : Decision.PUBLIC;
    }

    private static Decision scopeDecision(
            List<QuarkusSecurityPermission> permissions, QuarkusSecurityEndpoint endpoint, String scope) {
        List<QuarkusSecurityPermission> selected = new ArrayList<>();
        int longest = -1;
        boolean unknown = false;
        for (QuarkusSecurityPermission permission : permissions) {
            if (!scope.equals(permission.appliesTo())) {
                continue;
            }
            int rank = rank(permission.paths(), endpoint.path());
            if (rank == -2) {
                unknown = true;
                continue;
            }
            if (rank < 0) {
                continue;
            }
            if (permission.shared()) {
                Decision shared = matchingMethods(List.of(permission), endpoint.method());
                if (shared == Decision.RESTRICTED) {
                    return shared;
                }
                unknown |= shared == Decision.UNKNOWN;
            } else if (rank >= longest) {
                if (rank > longest) {
                    selected.clear();
                    longest = rank;
                }
                selected.add(permission);
            }
        }
        Decision result = selected.isEmpty() ? Decision.PUBLIC : matchingMethods(selected, endpoint.method());
        return result == Decision.RESTRICTED ? result : unknown ? Decision.UNKNOWN : result;
    }

    private static Decision matchingMethods(List<QuarkusSecurityPermission> permissions, String method) {
        List<QuarkusSecurityPermission> specific = new ArrayList<>();
        List<QuarkusSecurityPermission> all = new ArrayList<>();
        for (QuarkusSecurityPermission permission : permissions) {
            if (permission.methods() == null || permission.methods().isBlank()) {
                all.add(permission);
            } else {
                for (String candidate : permission.methods().split(",")) {
                    if (candidate.trim().equals(method)) {
                        specific.add(permission);
                        break;
                    }
                }
            }
        }
        List<QuarkusSecurityPermission> selected = specific.isEmpty() ? all : specific;
        // Quarkus denies a matching path for which no configured method mapping matches.
        if (selected.isEmpty()) {
            return Decision.RESTRICTED;
        }
        boolean unknown = false;
        for (QuarkusSecurityPermission permission : selected) {
            if (!permission.knownPolicy()) {
                unknown = true;
            } else if (!"permit".equals(permission.policy())) {
                return Decision.RESTRICTED;
            }
        }
        return unknown ? Decision.UNKNOWN : Decision.PUBLIC;
    }

    private static int rank(String paths, String target) {
        if (paths == null || paths.isBlank()) {
            return -2;
        }
        int rank = -1;
        for (String item : paths.split(",")) {
            String path = item.trim();
            if (path.contains("{") || (path.contains("*") && !path.endsWith("/*"))) {
                return -2;
            }
            if (path.endsWith("/*")) {
                String prefix = path.substring(0, path.length() - 1);
                if (target.startsWith(prefix) || target.equals(prefix.substring(0, prefix.length() - 1))) {
                    rank = Math.max(rank, (prefix.length() - 1) * 2);
                }
            } else if (path.equals(target)) {
                rank = Math.max(rank, path.length() * 2 + 1);
            }
        }
        return rank;
    }
}
