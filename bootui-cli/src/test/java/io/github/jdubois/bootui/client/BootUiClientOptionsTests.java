package io.github.jdubois.bootui.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BootUiClientOptionsTests {

    @Test
    void defaultsMatchAFreshlyStartedLocalApplication() {
        BootUiClientOptions options = BootUiClientOptions.defaults();

        assertThat(options.baseUrl()).isEqualTo("http://localhost:8080");
        assertThat(options.apiPath()).isEqualTo("/bootui/api");
        assertThat(options.token()).isNull();
        assertThat(options.cliEndpoint()).isEqualTo("http://localhost:8080/bootui/api/cli");
        assertThat(options.toolEndpoint("get_beans")).isEqualTo("http://localhost:8080/bootui/api/cli/tools/get_beans");
    }

    @Test
    void aBareHostAndPortIsTreatedAsHttpBecauseThatIsWhatPeopleType() {
        assertThat(new BootUiClientOptions("localhost:8184", null, null, null).baseUrl())
                .isEqualTo("http://localhost:8184");
        assertThat(new BootUiClientOptions("https://app.internal", null, null, null).baseUrl())
                .isEqualTo("https://app.internal");
    }

    @Test
    void trailingAndMissingSlashesAreNormalizedSoTheUrlNeverDoubles() {
        BootUiClientOptions options = new BootUiClientOptions("http://localhost:8080///", "console/api/", null, null);

        assertThat(options.cliEndpoint()).isEqualTo("http://localhost:8080/console/api/cli");
        assertThat(options.apiEndpoint("mcp-server")).isEqualTo("http://localhost:8080/console/api/mcp-server");
        assertThat(options.apiEndpoint("/mcp-server")).isEqualTo("http://localhost:8080/console/api/mcp-server");
    }

    @Test
    void blankValuesFallBackToTheDefaultsRatherThanProducingAnUnusableUrl() {
        BootUiClientOptions options = new BootUiClientOptions("  ", "  ", "  ", Duration.ZERO);

        assertThat(options.baseUrl()).isEqualTo("http://localhost:8080");
        assertThat(options.apiPath()).isEqualTo("/bootui/api");
        assertThat(options.token()).isNull();
        assertThat(options.timeout()).isEqualTo(BootUiClientOptions.DEFAULT_TIMEOUT);
    }

    @Test
    void aNegativeTimeoutFallsBackRatherThanFailingEveryRequestImmediately() {
        assertThat(new BootUiClientOptions(null, null, null, Duration.ofSeconds(-1)).timeout())
                .isEqualTo(BootUiClientOptions.DEFAULT_TIMEOUT);
    }

    @Test
    void theTokenIsRedactedFromToStringSoLoggingTheOptionsCannotLeakIt() {
        BootUiClientOptions options =
                new BootUiClientOptions("http://localhost:8080", "/bootui/api", "a-real-token", null);

        assertThat(options.toString()).doesNotContain("a-real-token").contains("******");
    }

    @Test
    void aBarePortIsReadAsLocalhostSoTheDocumentedShorthandProducesAUsableUrl() {
        assertThat(new BootUiClientOptions(":9000", null, null, null).cliEndpoint())
                .isEqualTo("http://localhost:9000/bootui/api/cli");
    }
}
