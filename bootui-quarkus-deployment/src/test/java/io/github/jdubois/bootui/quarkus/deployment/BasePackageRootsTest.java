package io.github.jdubois.bootui.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the build-time base-package antichain reduction. These pin the defensive guards the three
 * design critics converged on: the default package and single-segment roots must be dropped (either would
 * make ArchUnit scan the entire classpath), descendants fold into their ancestor, and the result is capped.
 */
class BasePackageRootsTest {

    @Test
    void dropsTheDefaultAndBlankPackages() {
        assertThat(BasePackageRoots.reduce(List.of("", "   ", "com.acme"))).containsExactly("com.acme");
    }

    @Test
    void ignoresNullEntries() {
        assertThat(BasePackageRoots.reduce(Arrays.asList(null, "com.acme", null)))
                .containsExactly("com.acme");
    }

    @Test
    void dropsSingleSegmentRoots() {
        // org / com / io alone would make ArchUnit scan that whole top-level namespace across every dependency.
        assertThat(BasePackageRoots.reduce(List.of("org", "com", "io", "com.acme.web")))
                .containsExactly("com.acme.web");
    }

    @Test
    void foldsDescendantsIntoTheirAncestor() {
        assertThat(BasePackageRoots.reduce(List.of("com.acme", "com.acme.web", "com.acme.web.api", "com.acme.svc")))
                .containsExactly("com.acme");
    }

    @Test
    void keepsDistinctSiblingRootsSorted() {
        assertThat(BasePackageRoots.reduce(List.of("com.acme.web", "com.acme.svc", "org.example.app")))
                .containsExactly("com.acme.svc", "com.acme.web", "org.example.app");
    }

    @Test
    void doesNotCollapsePackagesThatMerelyShareAPrefixString() {
        // "com.acme.b" is NOT an ancestor of "com.acme.bc" — only a dot-bounded prefix counts.
        assertThat(BasePackageRoots.reduce(List.of("com.acme.b", "com.acme.bc")))
                .containsExactly("com.acme.b", "com.acme.bc");
    }

    @Test
    void capsTheNumberOfRoots() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < BasePackageRoots.MAX_ROOTS + 25; i++) {
            many.add(String.format("com.acme.m%03d", i));
        }
        List<String> roots = BasePackageRoots.reduce(many);
        assertThat(roots).hasSize(BasePackageRoots.MAX_ROOTS);
        // Deterministic: the cap keeps the lexicographically-first roots.
        assertThat(roots).startsWith("com.acme.m000", "com.acme.m001").isSorted();
    }

    @Test
    void returnsEmptyForNoUsablePackages() {
        assertThat(BasePackageRoots.reduce(List.of("", "org", "com"))).isEmpty();
    }
}
