package io.github.jdubois.bootui.engine.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveNamesTests {

    @Test
    void recognizesKeywordNamesAndTheHeaderAllowList() {
        assertThat(SensitiveNames.isSensitive("apiKey")).isTrue();
        assertThat(SensitiveNames.isSensitive("access_token")).isTrue();
        assertThat(SensitiveNames.isSensitive("Authorization")).isTrue();
        assertThat(SensitiveNames.isSensitive("Set-Cookie")).isTrue();
        assertThat(SensitiveNames.isSensitive("X-XSRF-TOKEN")).isTrue();
    }

    @Test
    void leavesOrdinaryNamesAlone() {
        assertThat(SensitiveNames.isSensitive("page")).isFalse();
        assertThat(SensitiveNames.isSensitive("X-Trace")).isFalse();
        assertThat(SensitiveNames.isSensitive("content-length")).isFalse();
    }

    @Test
    void treatsNullAndBlankNamesAsNotSensitive() {
        assertThat(SensitiveNames.isSensitive(null)).isFalse();
        assertThat(SensitiveNames.isSensitive("")).isFalse();
        assertThat(SensitiveNames.isSensitive("   ")).isFalse();
    }

    @Test
    void recognizesPercentEncodedNames() {
        assertThat(SensitiveNames.isSensitive("%70assword")).isTrue();
        assertThat(SensitiveNames.isSensitive("api%2Dkey")).isTrue();
        assertThat(SensitiveNames.isSensitive("%41uthorization")).isTrue();
        assertThat(SensitiveNames.isSensitive("client%5Fsecret")).isTrue();
    }

    @Test
    void recognizesNamesHiddenBehindNonAsciiEncoding() {
        // A zero-width space in front of the keyword still decodes to a name containing "password".
        assertThat(SensitiveNames.isSensitive("%E2%80%8Bpassword")).isTrue();
    }

    @Test
    void treatsPlusAsSpaceInQueryNamesButNotInPathSegments() {
        assertThat(SensitiveNames.decodeQueryComponent("api+key")).isEqualTo("api key");
        assertThat(SensitiveNames.decodePathComponent("api+key")).isEqualTo("api+key");
    }

    @Test
    void survivesMalformedPercentEscapes() {
        assertThat(SensitiveNames.decodeQueryComponent("%")).isEqualTo("%");
        assertThat(SensitiveNames.decodeQueryComponent("%zz")).isEqualTo("%zz");
        assertThat(SensitiveNames.decodeQueryComponent("token%")).isEqualTo("token%");
        assertThat(SensitiveNames.isSensitive("token%")).isTrue();
        assertThat(SensitiveNames.isSensitive("%zz")).isFalse();
    }

    @Test
    void handlesNullAndEmptyDecodeInput() {
        assertThat(SensitiveNames.decodeQueryComponent(null)).isNull();
        assertThat(SensitiveNames.decodePathComponent(null)).isNull();
        assertThat(SensitiveNames.decodeQueryComponent("")).isEmpty();
    }

    @Test
    void doesNotDoubleDecode() {
        // %2570assword is literally "%70assword" to the server, not "password".
        assertThat(SensitiveNames.decodeQueryComponent("%2570assword")).isEqualTo("%70assword");
        assertThat(SensitiveNames.isSensitive("%2570assword")).isFalse();
    }
}
