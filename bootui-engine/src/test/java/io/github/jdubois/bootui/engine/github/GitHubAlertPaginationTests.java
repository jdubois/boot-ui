package io.github.jdubois.bootui.engine.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitHubAlertPaginationTests {

    private static final String PATH = "repos/owner/project/dependabot/alerts";
    private static final String ENDPOINT = "https://ghe.example/api/v3/" + PATH;

    @Test
    void traversesEveryNumberedPageInsteadOfExtrapolatingFromLast() throws Exception {
        GitHubAlertPagination pagination = pagination();
        pagination.acceptPage(40, "first");
        assertThat(pagination.nextPath(links("<" + ENDPOINT + "?page=3>; rel=\"last\"")))
                .isEqualTo(PATH + "?state=open&per_page=100&page=2");
        pagination.acceptPage(60, "second");
        assertThat(pagination.nextPath(links())).isEqualTo(PATH + "?state=open&per_page=100&page=3");
        pagination.acceptPage(51, "third");
        assertThat(pagination.nextPath(links())).isNull();
        assertThat(pagination.count()).isEqualTo(151);
    }

    @Test
    void canonicalizesCursorsAndRejectsCyclesWithoutLeakingThem() throws Exception {
        GitHubAlertPagination pagination = pagination();
        assertThat(pagination.nextPath(links("<?after=private%2Fcursor%3D>; rel=\"next\"")))
                .isEqualTo(PATH + "?state=open&per_page=100&after=private%2Fcursor%3D");
        assertThatThrownBy(() -> pagination.nextPath(links("<?after=private%2fcursor%3d>; rel=\"next\"")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("repeated")
                .hasMessageNotContaining("private");
    }

    @Test
    void acceptsSeparateLinkHeadersAndPreviousCursorOnTheTerminalPage() throws Exception {
        GitHubAlertPagination pagination = pagination();
        assertThat(pagination.nextPath(links("<?after=cursor>; rel=\"next\"", "<?before=previous>; rel=\"prev\"")))
                .endsWith("&after=cursor");
        assertThat(pagination.nextPath(links("<?before=cursor>; rel=\"prev\""))).isNull();
    }

    @Test
    void rejectsRepeatedPayloadWithoutCountingItTwice() throws Exception {
        GitHubAlertPagination pagination = pagination();
        pagination.acceptPage(100, "same page");
        assertThatThrownBy(() -> pagination.acceptPage(100, "same page"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("repeated");
        assertThat(pagination.count()).isEqualTo(100);
    }

    @ParameterizedTest
    @ValueSource(strings = {"public-numbered", "public-cursor", "enterprise-numbered", "enterprise-cursor"})
    void canonicalAliasesRequireTheMetadataIdAndKeepTheTrustedNamedEndpoint(String variant) throws Exception {
        URI base = URI.create(
                variant.startsWith("enterprise") ? "https://ghe.example/api/v3/" : "https://api.github.com/");
        String selector = variant.endsWith("cursor") ? "after=opaque" : "page=2";
        for (String suffix : List.of("dependabot/alerts", "code-scanning/alerts", "secret-scanning/alerts")) {
            String namedPath = "repos/owner/project/" + suffix;
            GitHubAlertPagination pagination =
                    new GitHubAlertPagination(base, namedPath + "?state=open&per_page=100", 1300192L);
            String target = base.resolve("repositories/1300192/" + suffix) + "?state=open&per_page=100&" + selector;
            assertThat(pagination.nextPath(links("<" + target + ">; rel=\"next\"", "<" + target + ">; rel=\"last\"")))
                    .isEqualTo(namedPath + "?state=open&per_page=100&" + selector);
            assertThat(pagination.nextPath(links())).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://ghe.example/api/v3/repositories/1300193/dependabot/alerts?page=2",
                "https://ghe.example/api/v3/repositories/not-numeric/dependabot/alerts?page=2",
                "https://ghe.example/api/v3/repositories/1300192/code-scanning/alerts?page=2",
                "https://ghe.example/repositories/1300192/dependabot/alerts?page=2",
                "https://foreign.example/api/v3/repositories/1300192/dependabot/alerts?page=2"
            })
    void canonicalAliasesCannotChangeIdentityEndpointOrOrigin(String target) {
        GitHubAlertPagination pagination = new GitHubAlertPagination(
                URI.create("https://ghe.example/api/v3/"), PATH + "?state=open&per_page=100", 1300192L);
        assertThatThrownBy(() -> pagination.nextPath(links("<" + target + ">; rel=\"next\"")))
                .isInstanceOf(IOException.class)
                .hasMessage("GitHub returned invalid or unsupported alert pagination.");
    }

    @Test
    void anUnresolvedMetadataIdCannotBeInferredFromTheFirstCanonicalLink() {
        for (Long id : new Long[] {null, 0L, -1L}) {
            GitHubAlertPagination pagination = new GitHubAlertPagination(
                    URI.create("https://ghe.example/api/v3/"), PATH + "?state=open&per_page=100", id);
            assertThatThrownBy(
                            () -> pagination.nextPath(
                                    links(
                                            "<https://ghe.example/api/v3/repositories/1300192/dependabot/alerts?page=2>; rel=\"next\"")))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void validatesImplicitInitialFirstAndPreviousReferencesWithoutRequiringAForwardSelector() throws Exception {
        GitHubAlertPagination pagination = pagination();
        assertThat(pagination.nextPath(links(
                        "<?state=open&per_page=100>; rel=\"first\"",
                        "<?state=open&per_page=100>; rel=\"prev\"",
                        "<?page=2>; rel=\"next\"")))
                .endsWith("&page=2");
        assertThat(pagination.nextPath(
                        links("<?state=open&per_page=100>; rel=\"first\"", "<?state=open&per_page=100>; rel=\"prev\"")))
                .isNull();
    }

    @Test
    void numberedNextOnlyCannotSwitchToCursorTraversal() throws Exception {
        GitHubAlertPagination pagination = pagination();
        assertThat(pagination.nextPath(links("<?page=2>; rel=\"next\""))).endsWith("&page=2");
        assertThatThrownBy(() -> pagination.nextPath(links("<?after=opaque>; rel=\"next\"")))
                .isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "<?page=2>; rel=\"next\", <?page=1>; rel=\"last\"",
                "<?page=2>; rel=\"next\", <?after=opaque>; rel=\"last\"",
                "<?after=opaque>; rel=\"next\", <?page=2>; rel=\"last\""
            })
    void contradictoryFirstPageHintsCannotAuthorizeAnotherRequest(String header) {
        assertThatThrownBy(() -> pagination().nextPath(links(header))).isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"decreasing-last", "next-beyond-last", "last-behind-current"})
    void numberedHintsStayConsistentWithCurrentAndPreviouslyAdvertisedPages(String variant) throws Exception {
        GitHubAlertPagination pagination = pagination();
        String initial =
                switch (variant) {
                    case "decreasing-last" -> "<?page=2>; rel=\"next\", <?page=3>; rel=\"last\"";
                    case "next-beyond-last" -> "<?page=2>; rel=\"next\", <?page=2>; rel=\"last\"";
                    default -> "<?page=2>; rel=\"next\"";
                };
        assertThat(pagination.nextPath(links(initial))).endsWith("&page=2");
        String contradictory =
                switch (variant) {
                    case "decreasing-last" -> "<?page=3>; rel=\"next\", <?page=2>; rel=\"last\"";
                    case "next-beyond-last" -> "<?page=3>; rel=\"next\"";
                    default -> "<?page=1>; rel=\"last\"";
                };
        assertThatThrownBy(() -> pagination.nextPath(links(contradictory))).isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "<http://ghe.example/api/v3/repos/owner/project/dependabot/alerts?page=2>; rel=\"next\"",
                "<https://ghe.example:8443/api/v3/repos/owner/project/dependabot/alerts?page=2>; rel=\"next\"",
                "<https://elsewhere.example/api/v3/repos/owner/project/dependabot/alerts?page=2>; rel=\"next\"",
                "<https://user@ghe.example/api/v3/repos/owner/project/dependabot/alerts?page=2>; rel=\"next\"",
                "<https://ghe.example/api/v3/repos/other/project/dependabot/alerts?page=2>; rel=\"next\"",
                "<https://ghe.example/api/v3/repos/owner/project/code-scanning/alerts?page=2>; rel=\"next\"",
                "<https://ghe.example/repos/owner/project/dependabot/alerts?page=2>; rel=\"next\"",
                "<?page=2#private>; rel=\"next\"",
                "<?page=2&state=closed>; rel=\"next\"",
                "<?page=2&per_page=1>; rel=\"next\"",
                "<?page=2&sort=created>; rel=\"next\"",
                "<?page=2&page=3>; rel=\"next\"",
                "<?page=2&after=private>; rel=\"next\"",
                "<?before=private>; rel=\"next\"",
                "<?page=1>; rel=\"next\"",
                "<?page=3>; rel=\"next\"",
                "<?state=open&per_page=100>; rel=\"next\"",
                "<?state=open&per_page=100>; rel=\"last\"",
                "<?state=closed&per_page=100>; rel=\"first\"",
                "<https://foreign.example/api/v3/repos/owner/project/dependabot/alerts?state=open&per_page=100>; rel=\"prev\"",
                "<?page=0>; rel=\"last\"",
                "<?page=2147483648>; rel=\"last\"",
                "<?page=999999999999999999999999999999>; rel=\"next\"",
                "<?after=%zz>; rel=\"next\"",
                "<?after=%FF>; rel=\"next\"",
                "<?after=private%0A>; rel=\"next\"",
                "<?after=>; rel=\"next\"",
                "<http://[invalid?page=2>; rel=\"last\"",
                "<?page=2>; rel=\"next\", <?page=3>; rel=\"next\"",
                "private malformed header",
                "",
                "<?page=2>; rel=\"unsupported\""
            })
    void rejectsMalformedUnsafeOrUnsupportedLinksWithSafeErrors(String header) {
        assertThatThrownBy(() -> pagination().nextPath(links(header)))
                .isInstanceOf(IOException.class)
                .hasMessage("GitHub returned invalid or unsupported alert pagination.");
    }

    @Test
    void cannotCompleteAnUnsupportedLastCursorWithoutANextLink() {
        assertThatThrownBy(() -> pagination().nextPath(links("<?after=private>; rel=\"last\"")))
                .isInstanceOf(IOException.class);
    }

    private GitHubAlertPagination pagination() {
        return new GitHubAlertPagination(URI.create("https://ghe.example/api/v3/"), PATH + "?state=open&per_page=100");
    }

    private HttpHeaders links(String... values) {
        return HttpHeaders.of(values.length == 0 ? Map.of() : Map.of("Link", List.of(values)), (name, value) -> true);
    }
}
