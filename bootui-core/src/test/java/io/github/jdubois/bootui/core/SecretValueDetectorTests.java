package io.github.jdubois.bootui.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretValueDetectorTests {

    @Test
    void detectsUrlUserinfoCredentials() {
        assertThat(SecretValueDetector.looksLikeSecret("jdbc:postgresql://alice:s3cret@db:5432/app"))
                .isTrue();
        assertThat(SecretValueDetector.looksLikeSecret("https://user:pass@example.com/path"))
                .isTrue();
    }

    @Test
    void detectsUrlQueryParameterCredentials() {
        assertThat(SecretValueDetector.looksLikeSecret("jdbc:mysql://db/app?user=alice&password=s3cret"))
                .isTrue();
        assertThat(SecretValueDetector.looksLikeSecret("https://api.example.com/v1?api_key=abcdef"))
                .isTrue();
        assertThat(SecretValueDetector.looksLikeSecret("https://idp.example.com/cb?access_token=abc123"))
                .isTrue();
        assertThat(SecretValueDetector.looksLikeSecret("https://idp.example.com/cb?refresh_token=abc123"))
                .isTrue();
        assertThat(SecretValueDetector.looksLikeSecret("https://app.example.com/?client_secret=abc123"))
                .isTrue();
    }

    @Test
    void ignoresBenignQueryParameters() {
        assertThat(SecretValueDetector.looksLikeSecret("https://example.com/?token_type=Bearer"))
                .isFalse();
        assertThat(SecretValueDetector.looksLikeSecret("https://example.com/?section=secret-garden"))
                .isFalse();
    }

    @Test
    void detectsJwtTokens() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
        assertThat(SecretValueDetector.looksLikeSecret(jwt)).isTrue();
    }

    @Test
    void detectsAwsAccessKeys() {
        assertThat(SecretValueDetector.looksLikeSecret("AKIAIOSFODNN7EXAMPLE")).isTrue();
        assertThat(SecretValueDetector.looksLikeSecret("ASIAIOSFODNN7EXAMPLE")).isTrue();
    }

    @Test
    void detectsPemPrivateKeys() {
        assertThat(SecretValueDetector.looksLikeSecret("-----BEGIN RSA PRIVATE KEY-----\nMIIE...\n-----END"))
                .isTrue();
    }

    @Test
    void ignoresBenignValues() {
        assertThat(SecretValueDetector.looksLikeSecret("jdbc:postgresql://db:5432/app"))
                .isFalse();
        assertThat(SecretValueDetector.looksLikeSecret("https://example.com/path?user=alice"))
                .isFalse();
        assertThat(SecretValueDetector.looksLikeSecret("checkout-service")).isFalse();
        assertThat(SecretValueDetector.looksLikeSecret("8080")).isFalse();
        assertThat(SecretValueDetector.looksLikeSecret("")).isFalse();
        assertThat(SecretValueDetector.looksLikeSecret(null)).isFalse();
        assertThat(SecretValueDetector.looksLikeSecret(8080)).isFalse();
    }
}
