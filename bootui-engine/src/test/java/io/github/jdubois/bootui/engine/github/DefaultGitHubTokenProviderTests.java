package io.github.jdubois.bootui.engine.github;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.github.GitHubTokenProvider.Token;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DefaultGitHubTokenProviderTests {

    @Test
    void githubTokenIsPreferredOverGhToken() {
        Token token = environmentOnly("GITHUB_TOKEN", "primary", "GH_TOKEN", "secondary")
                .token("github.com", Duration.ofSeconds(1));

        assertThat(token.value()).isEqualTo("primary");
        assertThat(token.source()).isEqualTo("GITHUB_TOKEN");
    }

    @Test
    void blankGithubTokenFallsBackToTrimmedGhToken() {
        Token token = environmentOnly("GITHUB_TOKEN", "   ", "GH_TOKEN", "  secondary\n")
                .token("github.com", Duration.ofSeconds(1));

        assertThat(token.value()).isEqualTo("secondary");
        assertThat(token.source()).isEqualTo("GH_TOKEN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GITHUB.COM", "ssh.github.com", "SSH.GITHUB.COM", " GITHUB.COM \n", " \tssh.github.com "})
    void publicGithubAliasesUseThePublicCredential(String host) {
        Token token = environmentOnly("GITHUB_TOKEN", "public-token").token(host, Duration.ofSeconds(1));

        assertThat(token.value()).isEqualTo("public-token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ghe-one.example", "ghe-two.example"})
    void enterpriseEnvironmentTokensRequireAMatchingExplicitHost(String host) {
        Token token = environmentOnly(
                        "GH_HOST", " \t" + host.toUpperCase(java.util.Locale.ROOT) + "\n ",
                        "GH_TOKEN", "public-token",
                        "GITHUB_TOKEN", "other-public-token",
                        "GH_ENTERPRISE_TOKEN", "enterprise-token",
                        "GITHUB_ENTERPRISE_TOKEN", "other-enterprise-token")
                .token(" \t" + host + " \n", Duration.ofSeconds(1));

        assertThat(token.value()).isEqualTo("enterprise-token");
        assertThat(token.source()).isEqualTo("GH_ENTERPRISE_TOKEN");
    }

    @Test
    void blankEnterpriseTokenFallsBackToTheOtherEnterpriseVariable() {
        Token token = environmentOnly(
                        "GH_HOST", "ghe-one.example",
                        "GH_ENTERPRISE_TOKEN", " ",
                        "GITHUB_ENTERPRISE_TOKEN", "  enterprise-token\n")
                .token("ghe-one.example", Duration.ofSeconds(1));

        assertThat(token.value()).isEqualTo("enterprise-token");
        assertThat(token.source()).isEqualTo("GITHUB_ENTERPRISE_TOKEN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"github.com", "ghe-one.example", "ghe-two.example"})
    void cliLookupIsHostSpecificAndCannotInheritTokensForAnotherHost(String host) {
        StubProcess process = new StubProcess("stored-for-" + host + "\n");
        DefaultGitHubTokenProvider provider = new DefaultGitHubTokenProvider(
                Map.of(
                        "GH_HOST", "other.example",
                        "GH_ENTERPRISE_TOKEN", "wrong-enterprise-token",
                        "GITHUB_ENTERPRISE_TOKEN", "another-wrong-token",
                        "GH_CONFIG_DIR", "/fixture/gh"),
                builder -> {
                    assertThat(builder.command()).containsExactly("gh", "auth", "token", "--hostname", host);
                    assertThat(builder.environment())
                            .doesNotContainKeys(
                                    "GITHUB_TOKEN", "GH_TOKEN", "GITHUB_ENTERPRISE_TOKEN", "GH_ENTERPRISE_TOKEN")
                            .containsEntry("GH_HOST", host)
                            .containsEntry("GH_CONFIG_DIR", "/fixture/gh")
                            .containsEntry("GH_PROMPT_DISABLED", "1")
                            .containsEntry("GH_NO_UPDATE_NOTIFIER", "1");
                    assertThat(builder.redirectError()).isEqualTo(ProcessBuilder.Redirect.DISCARD);
                    return process;
                });

        Token token = provider.token(host, Duration.ofMillis(37));

        assertThat(token.value()).isEqualTo("stored-for-" + host);
        assertThat(token.source()).isEqualTo("gh auth token");
        assertThat(process.waitMillis).isEqualTo(37);
        assertThat(process.forciblyDestroyed).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "github.com", "ghe-two.example", "ghe-one.example:8443"})
    void anUnboundOrDifferentEnterpriseEnvironmentCannotAuthenticateAnotherHost(String environmentHost) {
        StubProcess process = new StubProcess("");
        DefaultGitHubTokenProvider provider = new DefaultGitHubTokenProvider(
                Map.of(
                        "GH_HOST", environmentHost,
                        "GITHUB_TOKEN", "public-token",
                        "GH_TOKEN", "other-public-token",
                        "GITHUB_ENTERPRISE_TOKEN", "enterprise-token",
                        "GH_ENTERPRISE_TOKEN", "other-enterprise-token"),
                builder -> {
                    assertThat(builder.command())
                            .containsExactly("gh", "auth", "token", "--hostname", "ghe-one.example");
                    assertThat(builder.environment())
                            .doesNotContainKeys(
                                    "GITHUB_TOKEN", "GH_TOKEN", "GITHUB_ENTERPRISE_TOKEN", "GH_ENTERPRISE_TOKEN");
                    return process;
                });

        assertThat(provider.token("ghe-one.example", Duration.ofSeconds(1))).isNull();
    }

    @Test
    void enterpriseVariablesWithoutGhHostAreNotUsed() {
        DefaultGitHubTokenProvider provider = new DefaultGitHubTokenProvider(
                Map.of("GH_ENTERPRISE_TOKEN", "unbound-token"), builder -> new StubProcess(""));

        assertThat(provider.token("ghe-one.example", Duration.ofSeconds(1))).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\n"})
    void matchingEnterpriseHostDoesNotReusePublicTokensWhenEnterpriseTokensAreMissing(String enterpriseToken) {
        Map<String, String> environment = new LinkedHashMap<>(Map.of(
                "GH_HOST", "ghe-one.example",
                "GITHUB_TOKEN", "public-token",
                "GH_TOKEN", "other-public-token"));
        if (enterpriseToken != null) {
            environment.put("GH_ENTERPRISE_TOKEN", enterpriseToken);
            environment.put("GITHUB_ENTERPRISE_TOKEN", enterpriseToken);
        }
        StubProcess process = new StubProcess("");
        DefaultGitHubTokenProvider provider = new DefaultGitHubTokenProvider(environment, builder -> {
            assertThat(builder.command()).containsExactly("gh", "auth", "token", "--hostname", "ghe-one.example");
            assertThat(builder.environment())
                    .doesNotContainKeys("GITHUB_TOKEN", "GH_TOKEN", "GITHUB_ENTERPRISE_TOKEN", "GH_ENTERPRISE_TOKEN")
                    .containsEntry("GH_HOST", "ghe-one.example");
            return process;
        });

        assertThat(provider.token("ghe-one.example", Duration.ofMillis(37))).isNull();
        assertThat(process.waitMillis).isEqualTo(37);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "GITHUB.COM", "SSH.GITHUB.COM", "GHE-ONE.EXAMPLE",
                " GITHUB.COM \n", " \tSSH.GITHUB.COM ", " \tGHE-ONE.EXAMPLE\n "
            })
    void cliReceivesTheCanonicalHost(String host) {
        String expected = host.trim().startsWith("GHE") ? "ghe-one.example" : "github.com";
        DefaultGitHubTokenProvider provider = new DefaultGitHubTokenProvider(Map.of(), builder -> {
            assertThat(builder.command()).containsExactly("gh", "auth", "token", "--hostname", expected);
            return new StubProcess("stored-token");
        });

        assertThat(provider.token(host, Duration.ofSeconds(1)).value()).isEqualTo("stored-token");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\r\n"})
    void missingRepositoryHostCannotFallBackToAnyCredential(String host) {
        assertThat(environmentOnly("GITHUB_TOKEN", "public-token").token(host, Duration.ofSeconds(1)))
                .isNull();
    }

    @Test
    void legacyProvidersRemainUsableForPublicGithubButNotEnterprise() {
        GitHubTokenProvider provider = timeout -> new Token("public-token", "legacy");

        assertThat(provider.token("github.com", Duration.ofSeconds(1)).value()).isEqualTo("public-token");
        assertThat(provider.token("ssh.github.com", Duration.ofSeconds(1)).value())
                .isEqualTo("public-token");
        assertThat(provider.token(" \tGITHUB.COM\n ", Duration.ofSeconds(1)).value())
                .isEqualTo("public-token");
        assertThat(provider.token("ghe-one.example", Duration.ofSeconds(1))).isNull();
        assertThat(provider.token("ghe-two.example", Duration.ofSeconds(1))).isNull();
        assertThat(provider.token(null, Duration.ofSeconds(1))).isNull();
        assertThat(provider.token(" \t\n", Duration.ofSeconds(1))).isNull();
    }

    @Test
    void defaultProvidersLegacyMethodRemainsPublicGithubOnly() {
        assertThat(environmentOnly("GITHUB_TOKEN", "public-token")
                        .token(Duration.ofSeconds(1))
                        .value())
                .isEqualTo("public-token");
    }

    @Test
    void missingCliAndFailedLookupReturnNoCredential() {
        DefaultGitHubTokenProvider missingCli = new DefaultGitHubTokenProvider(Map.of(), builder -> {
            throw new IOException("fixture stderr must not escape");
        });
        StubProcess failedProcess = new StubProcess("not-a-successful-token");
        failedProcess.exitCode = 1;
        DefaultGitHubTokenProvider failedLookup = new DefaultGitHubTokenProvider(Map.of(), builder -> failedProcess);

        assertThat(missingCli.token("github.com", Duration.ofSeconds(1))).isNull();
        assertThat(failedLookup.token("ghe-one.example", Duration.ofSeconds(1))).isNull();
        assertThat(failedProcess.forciblyDestroyed).isTrue();
    }

    @Test
    void timedOutLookupDiscardsOutputAndTerminatesTheProcess() {
        StubProcess process = new StubProcess("partial-token");
        process.finished = false;
        DefaultGitHubTokenProvider provider = new DefaultGitHubTokenProvider(Map.of(), builder -> process);

        assertThat(provider.token("ghe-one.example", Duration.ofMillis(12))).isNull();
        assertThat(process.waitMillis).isEqualTo(12);
        assertThat(process.forciblyDestroyed).isTrue();
        assertThat(process.destroyed).isFalse();
    }

    @Test
    void interruptedLookupPreservesTheInterruptAndTerminatesTheProcess() {
        StubProcess process = new StubProcess("partial-token");
        process.interrupted = true;
        DefaultGitHubTokenProvider provider = new DefaultGitHubTokenProvider(Map.of(), builder -> process);

        try {
            assertThat(provider.token("ghe-two.example", Duration.ofSeconds(1))).isNull();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(process.forciblyDestroyed).isTrue();
            assertThat(process.destroyed).isFalse();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void missingAndNonPositiveTimeoutsUseTheBoundedDefault() {
        for (Duration timeout : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            StubProcess process = new StubProcess("stored-token");
            DefaultGitHubTokenProvider provider = new DefaultGitHubTokenProvider(Map.of(), builder -> process);
            assertThat(provider.token("github.com", timeout)).isNotNull();
            assertThat(process.waitMillis).isEqualTo(5000);
        }
    }

    @Test
    void credentialStringRepresentationNeverContainsItsValue() {
        assertThat(new Token("secret-value", "GITHUB_TOKEN").toString()).doesNotContain("secret-value");
    }

    @Test
    void createDoesNotStartCredentialLookup() {
        assertThat(DefaultGitHubTokenProvider.create()).isNotNull();
    }

    private static DefaultGitHubTokenProvider environmentOnly(String... pairs) {
        Map<String, String> environment = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            environment.put(pairs[i], pairs[i + 1]);
        }
        return new DefaultGitHubTokenProvider(environment, builder -> {
            throw new AssertionError("Unexpected CLI lookup");
        });
    }

    private static final class StubProcess extends Process {
        private final InputStream output;
        private boolean finished = true;
        private boolean interrupted;
        private boolean destroyed;
        private boolean forciblyDestroyed;
        private int exitCode;
        private long waitMillis;

        private StubProcess(String output) {
            this.output = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return output;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            if (interrupted) {
                throw new InterruptedException("fixture interrupt");
            }
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            waitMillis = unit.toMillis(timeout);
            waitFor();
            return finished;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public Process destroyForcibly() {
            forciblyDestroyed = true;
            return this;
        }
    }
}
