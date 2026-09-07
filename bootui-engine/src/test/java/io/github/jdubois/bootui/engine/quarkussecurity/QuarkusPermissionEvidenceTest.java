package io.github.jdubois.bootui.engine.quarkussecurity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.spi.QuarkusSecurityEndpoint;
import io.github.jdubois.bootui.spi.QuarkusSecurityPermission;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuarkusPermissionEvidenceTest {
    private static QuarkusSecurityEndpoint endpoint(String path, String method) {
        return new QuarkusSecurityEndpoint(path, method, QuarkusSecurityEndpoint.Access.UNANNOTATED, false);
    }

    @Test
    void slashIsExactAndWildcardCoversDescendants() {
        var exact = List.of(new QuarkusSecurityPermission("root", "/", "deny", null));
        assertThat(QuarkusPermissionEvidence.decision(exact, endpoint("/", "GET")))
                .isEqualTo(QuarkusPermissionEvidence.Decision.RESTRICTED);
        assertThat(QuarkusPermissionEvidence.decision(exact, endpoint("/api", "GET")))
                .isEqualTo(QuarkusPermissionEvidence.Decision.PUBLIC);
        assertThat(QuarkusPermissionEvidence.decision(
                        List.of(new QuarkusSecurityPermission("root", "/*", "authenticated", null)),
                        endpoint("/api", "GET")))
                .isEqualTo(QuarkusPermissionEvidence.Decision.RESTRICTED);
    }

    @Test
    void unmatchedMethodIsDeniedEvenForPermitPolicy() {
        var permissions = List.of(new QuarkusSecurityPermission("get", "/api/*", "permit", "GET"));
        assertThat(QuarkusPermissionEvidence.decision(permissions, endpoint("/api/order", "GET")))
                .isEqualTo(QuarkusPermissionEvidence.Decision.PUBLIC);
        assertThat(QuarkusPermissionEvidence.decision(permissions, endpoint("/api/order", "POST")))
                .isEqualTo(QuarkusPermissionEvidence.Decision.RESTRICTED);
    }

    @Test
    void longerPathWinsAndExactBeatsPrefix() {
        var permissions = List.of(
                new QuarkusSecurityPermission("all", "/*", "authenticated", null),
                new QuarkusSecurityPermission("public", "/api/*", "permit", null),
                new QuarkusSecurityPermission("specific", "/api/private", "deny", null));
        assertThat(QuarkusPermissionEvidence.decision(permissions, endpoint("/api/public", "GET")))
                .isEqualTo(QuarkusPermissionEvidence.Decision.PUBLIC);
        assertThat(QuarkusPermissionEvidence.decision(permissions, endpoint("/api/private", "GET")))
                .isEqualTo(QuarkusPermissionEvidence.Decision.RESTRICTED);
        assertThat(QuarkusPermissionEvidence.decision(
                        List.of(
                                new QuarkusSecurityPermission("prefix", "/api/*", "deny", null),
                                new QuarkusSecurityPermission("exact", "/api", "permit", null)),
                        endpoint("/api", "GET")))
                .isEqualTo(QuarkusPermissionEvidence.Decision.PUBLIC);
    }

    @Test
    void samePathRestrictionsAndSharedPoliciesStillApply() {
        var same = List.of(
                new QuarkusSecurityPermission("public", "/*", "permit", null),
                new QuarkusSecurityPermission("private", "/*", "authenticated", null));
        assertThat(QuarkusPermissionEvidence.decision(same, endpoint("/api", "GET")))
                .isEqualTo(QuarkusPermissionEvidence.Decision.RESTRICTED);
        var shared = List.of(
                new QuarkusSecurityPermission("public", "/api", "permit", null),
                new QuarkusSecurityPermission("shared", "/*", "authenticated", null, true, "all", true));
        assertThat(QuarkusPermissionEvidence.decision(shared, endpoint("/api", "GET")))
                .isEqualTo(QuarkusPermissionEvidence.Decision.RESTRICTED);
    }

    @Test
    void methodSpecificMappingWinsOverUnqualifiedAtSamePath() {
        var mappings = List.of(
                new QuarkusSecurityPermission("all", "/api", "deny", null),
                new QuarkusSecurityPermission("get", "/api", "permit", "GET"));
        assertThat(QuarkusPermissionEvidence.decision(mappings, endpoint("/api", "GET")))
                .isEqualTo(QuarkusPermissionEvidence.Decision.PUBLIC);
        assertThat(QuarkusPermissionEvidence.decision(mappings, endpoint("/api", "POST")))
                .isEqualTo(QuarkusPermissionEvidence.Decision.RESTRICTED);
    }

    @Test
    void unsupportedPoliciesAndPathsAreUnknownNotPublicOrProtected() {
        assertThat(QuarkusPermissionEvidence.decision(
                        List.of(new QuarkusSecurityPermission("custom", "/*", "business", null)),
                        endpoint("/api", "GET")))
                .isEqualTo(QuarkusPermissionEvidence.Decision.UNKNOWN);
        assertThat(QuarkusPermissionEvidence.decision(List.of(), endpoint("/api/{id}", "GET")))
                .isEqualTo(QuarkusPermissionEvidence.Decision.UNKNOWN);
        assertThat(QuarkusPermissionEvidence.decision(
                        List.of(new QuarkusSecurityPermission("scope", "/*", "permit", null, false, "custom", true)),
                        endpoint("/api", "GET")))
                .isEqualTo(QuarkusPermissionEvidence.Decision.UNKNOWN);
    }

    @Test
    void httpAndJaxrsHaveIndependentLongestMatchesAndIntersectRestrictions() {
        for (String restrictiveScope : List.of("ALL", "JAXRS")) {
            String other = restrictiveScope.equals("ALL") ? "JAXRS" : "ALL";
            var permissions = List.of(
                    new QuarkusSecurityPermission("deny", "/*", "deny", null, false, restrictiveScope, true),
                    new QuarkusSecurityPermission("permit", "/api", "permit", null, false, other, true));
            assertThat(QuarkusPermissionEvidence.decision(permissions, endpoint("/api", "GET")))
                    .isEqualTo(QuarkusPermissionEvidence.Decision.RESTRICTED);
            var sameScopeOverride = new java.util.ArrayList<>(permissions);
            sameScopeOverride.add(
                    new QuarkusSecurityPermission("override", "/api", "permit", null, false, restrictiveScope, true));
            assertThat(QuarkusPermissionEvidence.decision(sameScopeOverride, endpoint("/api", "GET")))
                    .isEqualTo(QuarkusPermissionEvidence.Decision.PUBLIC);
        }
    }

    @Test
    void independentScopesRetainMethodMismatchAndSharedRestrictions() {
        var methods = List.of(
                new QuarkusSecurityPermission("get", "/*", "permit", "GET", false, "ALL", true),
                new QuarkusSecurityPermission("rest", "/api", "permit", null, false, "JAXRS", true));
        assertThat(QuarkusPermissionEvidence.decision(methods, endpoint("/api", "GET")))
                .isEqualTo(QuarkusPermissionEvidence.Decision.PUBLIC);
        assertThat(QuarkusPermissionEvidence.decision(methods, endpoint("/api", "POST")))
                .isEqualTo(QuarkusPermissionEvidence.Decision.RESTRICTED);
        var shared = List.of(
                new QuarkusSecurityPermission("shared", "/*", "authenticated", null, true, "JAXRS", true),
                new QuarkusSecurityPermission("rest", "/api", "permit", null, false, "JAXRS", true),
                new QuarkusSecurityPermission("http", "/api", "permit", null, false, "ALL", true));
        assertThat(QuarkusPermissionEvidence.decision(shared, endpoint("/api", "GET")))
                .isEqualTo(QuarkusPermissionEvidence.Decision.RESTRICTED);
    }

    @Test
    void aPublicScopeCannotTurnAnUnknownScopeIntoProof() {
        for (String unknownScope : List.of("ALL", "JAXRS", "unsupported")) {
            var permissions = List.of(
                    new QuarkusSecurityPermission("custom", "/*", "custom", null, true, unknownScope, false),
                    new QuarkusSecurityPermission("http", "/api", "permit", null, false, "ALL", true),
                    new QuarkusSecurityPermission("rest", "/api", "permit", null, false, "JAXRS", true));
            assertThat(QuarkusPermissionEvidence.decision(permissions, endpoint("/api", "GET")))
                    .isEqualTo(QuarkusPermissionEvidence.Decision.UNKNOWN);
        }
    }
}
