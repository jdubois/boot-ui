package io.github.jdubois.bootui.engine.httpclient;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The HTTP Clients panel renders base URLs by default rather than on request, so its sanitizer has to be
 * stricter than the panel's value-exposure mode. These tests pin that stricter contract.
 */
class HttpClientUrlsTest {

    @Test
    @DisplayName("credentials embedded in the authority are removed under every exposure mode")
    void stripsUserInfo() {
        for (ValueExposure exposure : ValueExposure.values()) {
            assertThat(HttpClientUrls.sanitize("https://admin:hunter2@api.example.com/v1", exposure))
                    .as("exposure %s", exposure)
                    .isEqualTo("https://api.example.com/v1");
        }
    }

    @Test
    @DisplayName("an unparseable value is still stripped instead of being passed through")
    void stripsUserInfoFromUnparseableValue() {
        assertThat(HttpClientUrls.sanitize("https://user:pw@${service.host}/v1", ValueExposure.FULL))
                .isEqualTo("https://${service.host}/v1");
    }

    @Test
    @DisplayName("an at sign in the path is not mistaken for credentials")
    void keepsAtSignInPath() {
        assertThat(HttpClientUrls.sanitize("https://api.example.com/users/a@b.com", ValueExposure.FULL))
                .isEqualTo("https://api.example.com/users/a@b.com");
    }

    @Test
    @DisplayName("secret-named query values are masked even under FULL exposure")
    void masksSecretQueryValuesUnderFullExposure() {
        assertThat(HttpClientUrls.sanitize("https://api.example.com/v1?api-key=abc&page=2", ValueExposure.FULL))
                .isEqualTo("https://api.example.com/v1?api-key=******&page=2");
    }

    @Test
    @DisplayName("METADATA_ONLY drops the whole query string")
    void dropsQueryUnderMetadataOnly() {
        assertThat(HttpClientUrls.sanitize("https://api.example.com/v1?page=2", ValueExposure.METADATA_ONLY))
                .isEqualTo("https://api.example.com/v1");
    }

    @Test
    @DisplayName("a fragment is always removed")
    void stripsFragment() {
        assertThat(HttpClientUrls.sanitize("https://api.example.com/v1#token=abc", ValueExposure.FULL))
                .isEqualTo("https://api.example.com/v1");
    }

    @Test
    @DisplayName("a pathological value cannot bloat the response")
    void truncatesLongValues() {
        String longUrl = "https://api.example.com/" + "a".repeat(500);
        String sanitized = HttpClientUrls.sanitize(longUrl, ValueExposure.FULL);
        assertThat(sanitized).hasSize(HttpClientUrls.MAX_URL_LENGTH).endsWith("…");
    }

    @Test
    @DisplayName("blank input is normalized to null")
    void normalizesBlankInput() {
        assertThat(HttpClientUrls.sanitize("   ", ValueExposure.FULL)).isNull();
        assertThat(HttpClientUrls.sanitize(null, ValueExposure.FULL)).isNull();
    }

    @Test
    @DisplayName("an unresolved placeholder is detected and never yields a link host")
    void detectsUnresolvedPlaceholder() {
        assertThat(HttpClientUrls.hasUnresolvedPlaceholder("https://${service.host}/v1"))
                .isTrue();
        assertThat(HttpClientUrls.host("https://${service.host}/v1")).isNull();
        assertThat(HttpClientUrls.isResolvedAbsoluteUrl("https://${service.host}/v1"))
                .isFalse();
    }

    @Test
    @DisplayName("only an absolute URL with a scheme and a host counts as resolved")
    void recognizesResolvedAbsoluteUrls() {
        assertThat(HttpClientUrls.isResolvedAbsoluteUrl("https://api.example.com"))
                .isTrue();
        assertThat(HttpClientUrls.isResolvedAbsoluteUrl("/v1/orders")).isFalse();
        assertThat(HttpClientUrls.isResolvedAbsoluteUrl("api.example.com")).isFalse();
        assertThat(HttpClientUrls.isResolvedAbsoluteUrl("not a url at all")).isFalse();
    }

    @Test
    @DisplayName("hosts are lowercased so attribution is case-insensitive")
    void lowercasesHost() {
        assertThat(HttpClientUrls.host("https://API.Example.COM/v1")).isEqualTo("api.example.com");
    }

    @Test
    @DisplayName("a container host name java.net.URI rejects is still a resolved host")
    void acceptsHostNamesUriRejects() {
        assertThat(HttpClientUrls.host("http://my_service:8080/v1")).isEqualTo("my_service");
        assertThat(HttpClientUrls.isResolvedAbsoluteUrl("http://my_service:8080"))
                .isTrue();
        assertThat(HttpClientUrls.isResolvedAbsoluteUrl("http://api.example.com/{tenant}"))
                .isTrue();
    }

    @Test
    @DisplayName("an IPv6 literal keeps its brackets and loses its port")
    void parsesIpv6Authority() {
        assertThat(HttpClientUrls.host("http://[::1]:8080/v1")).isEqualTo("[::1]");
        assertThat(HttpClientUrls.host("http://[2001:DB8::1]/v1")).isEqualTo("[2001:db8::1]");
    }

    @Test
    @DisplayName("a value without a scheme names no host of its own")
    void requiresAScheme() {
        assertThat(HttpClientUrls.host("//api.example.com/v1")).isNull();
        assertThat(HttpClientUrls.host("1http://api.example.com")).isNull();
    }

    @Test
    @DisplayName("a percent-encoded secret parameter name is still masked")
    void masksEncodedSecretParameterNames() {
        assertThat(HttpClientUrls.sanitize("https://api.example.com/v1?api%5Fkey=abcd1234", ValueExposure.FULL))
                .doesNotContain("abcd1234");
    }

    @Test
    @DisplayName("the legacy semicolon query separator cannot hide a secret")
    void masksSemicolonSeparatedParameters() {
        assertThat(HttpClientUrls.sanitize("https://api.example.com/v1?page=1;api-key=abcd1234", ValueExposure.FULL))
                .doesNotContain("abcd1234")
                .contains("page=1");
    }

    @Test
    @DisplayName("a value-less secret parameter is masked rather than echoed")
    void masksValuelessSecretParameter() {
        assertThat(HttpClientUrls.sanitize("https://api.example.com/v1?api-key", ValueExposure.FULL))
                .doesNotContain("api-key");
    }

    @Test
    @DisplayName("credentials in a scheme-less authority are stripped too")
    void stripsUserInfoWithoutScheme() {
        String proxy = "operator:" + "hunter2" + "@proxy.corp.example:3128";
        assertThat(HttpClientUrls.sanitize(proxy, ValueExposure.FULL)).isEqualTo("proxy.corp.example:3128");
    }
}
