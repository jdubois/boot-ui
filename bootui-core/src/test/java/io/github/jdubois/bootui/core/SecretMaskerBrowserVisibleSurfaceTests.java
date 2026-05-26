package io.github.jdubois.bootui.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link SecretMasker} correctly masks every category of property
 * name that can reach the browser via {@code /bootui/api/**} endpoints (mainly
 * {@code ConfigController}).
 *
 * <p>For each sensitive category there is at least one <em>positive</em> test
 * (the name IS a secret → value gets masked) and one <em>negative</em> test
 * (an unrelated name → value passes through unchanged). The masked format is
 * asserted to be {@link SecretMasker#MASKED_VALUE} ({@code "******"}).</p>
 */
class SecretMaskerBrowserVisibleSurfaceTests {

    private final SecretMasker masker = new SecretMasker();

    // -------------------------------------------------------------------------
    // Category: passwords / passwd
    // -------------------------------------------------------------------------

    @Test
    void masksPasswordSuffix() {
        assertThat(masker.isSecret("spring.datasource.password")).isTrue();
        assertThat(masker.mask("spring.datasource.password", "s3cr3t")).isEqualTo(SecretMasker.MASKED_VALUE);
    }

    @Test
    void masksPasswdVariant() {
        assertThat(masker.isSecret("db.passwd")).isTrue();
        assertThat(masker.mask("db.passwd", "hunter2")).isEqualTo(SecretMasker.MASKED_VALUE);
    }

    @Test
    void doesNotMaskNonPasswordProperty() {
        assertThat(masker.isSecret("server.port")).isFalse();
        assertThat(masker.mask("server.port", 8080)).isEqualTo(8080);
    }

    // -------------------------------------------------------------------------
    // Category: secrets
    // -------------------------------------------------------------------------

    @Test
    void masksSecretInName() {
        assertThat(masker.isSecret("app.secret")).isTrue();
        assertThat(masker.mask("app.secret", "abc123")).isEqualTo(SecretMasker.MASKED_VALUE);
    }

    @Test
    void masksSecretSubstring() {
        assertThat(masker.isSecret("my.client-secret-value")).isTrue();
    }

    @Test
    void doesNotMaskNonSecretProperty() {
        assertThat(masker.isSecret("spring.application.name")).isFalse();
        assertThat(masker.mask("spring.application.name", "my-app")).isEqualTo("my-app");
    }

    // -------------------------------------------------------------------------
    // Category: tokens
    // -------------------------------------------------------------------------

    @Test
    void masksTokenInName() {
        assertThat(masker.isSecret("github.access.token")).isTrue();
        assertThat(masker.mask("github.access.token", "ghp_xxxx")).isEqualTo(SecretMasker.MASKED_VALUE);
    }

    @Test
    void masksTokenUpperCase() {
        assertThat(masker.isSecret("GITHUB_TOKEN")).isTrue();
    }

    @Test
    void doesNotMaskNonTokenProperty() {
        assertThat(masker.isSecret("server.connection-timeout")).isFalse();
    }

    // -------------------------------------------------------------------------
    // Category: keys / api-key / apikey
    // -------------------------------------------------------------------------

    @Test
    void masksApiKeyHyphenated() {
        assertThat(masker.isSecret("app.api-key")).isTrue();
        assertThat(masker.mask("app.api-key", "key-value-123")).isEqualTo(SecretMasker.MASKED_VALUE);
    }

    @Test
    void masksApiKeyNoHyphen() {
        assertThat(masker.isSecret("stripe.apikey")).isTrue();
    }

    @Test
    void masksGenericKeyProperty() {
        assertThat(masker.isSecret("encryption.key")).isTrue();
    }

    @Test
    void doesNotMaskKeylessProperty() {
        assertThat(masker.isSecret("spring.cache.type")).isFalse();
    }

    // -------------------------------------------------------------------------
    // Category: credentials / credential
    // -------------------------------------------------------------------------

    @Test
    void masksCredentialProperty() {
        assertThat(masker.isSecret("smtp.credential")).isTrue();
        assertThat(masker.mask("smtp.credential", "pass")).isEqualTo(SecretMasker.MASKED_VALUE);
    }

    @Test
    void masksCredentialsProperty() {
        assertThat(masker.isSecret("ldap.credentials")).isTrue();
    }

    @Test
    void doesNotMaskUnrelatedProperty() {
        assertThat(masker.isSecret("spring.profiles.active")).isFalse();
    }

    // -------------------------------------------------------------------------
    // Category: private (private key material)
    // -------------------------------------------------------------------------

    @Test
    void masksPrivateKeyProperty() {
        assertThat(masker.isSecret("ssl.private-key")).isTrue();
        assertThat(masker.mask("ssl.private-key", "-----BEGIN RSA PRIVATE KEY-----"))
                .isEqualTo(SecretMasker.MASKED_VALUE);
    }

    @Test
    void masksUpperCasePrivate() {
        assertThat(masker.isSecret("X-PRIVATE-HEADER")).isTrue();
    }

    @Test
    void doesNotMaskPublicProperty() {
        assertThat(masker.isSecret("ssl.public-certificate")).isFalse();
    }

    // -------------------------------------------------------------------------
    // Category: authorization / auth
    // -------------------------------------------------------------------------

    @Test
    void masksAuthorizationHeader() {
        assertThat(masker.isSecret("http.authorization")).isTrue();
        assertThat(masker.mask("http.authorization", "******"))
                .isEqualTo(SecretMasker.MASKED_VALUE);
    }

    @Test
    void masksAuthSubstring() {
        assertThat(masker.isSecret("basic.auth.password")).isTrue();
    }

    @Test
    void doesNotMaskNonAuthProperty() {
        assertThat(masker.isSecret("management.endpoints.web.exposure.include")).isFalse();
    }

    // -------------------------------------------------------------------------
    // Category: client-secret / client_secret (OAuth)
    // -------------------------------------------------------------------------

    @Test
    void masksOAuthClientSecretHyphenated() {
        assertThat(masker.isSecret("spring.security.oauth2.client.registration.github.client-secret")).isTrue();
        assertThat(masker.mask(
                "spring.security.oauth2.client.registration.github.client-secret",
                "super-secret")).isEqualTo(SecretMasker.MASKED_VALUE);
    }

    @Test
    void masksOAuthClientSecretUnderscored() {
        assertThat(masker.isSecret("oauth.client_secret")).isTrue();
    }

    @Test
    void doesNotMaskOAuthClientId() {
        // Note: the full spring.security.oauth2.* path contains "auth" so it IS masked.
        // Use a short form that would represent a non-secret identifier.
        assertThat(masker.isSecret("app.client-id")).isFalse();
    }

    // -------------------------------------------------------------------------
    // Category: session-id / session_id
    // -------------------------------------------------------------------------

    @Test
    void masksSessionIdHyphenated() {
        assertThat(masker.isSecret("server.session-id")).isTrue();
        assertThat(masker.mask("server.session-id", "abc-123")).isEqualTo(SecretMasker.MASKED_VALUE);
    }

    @Test
    void masksSessionIdUnderscored() {
        assertThat(masker.isSecret("custom.session_id")).isTrue();
    }

    @Test
    void doesNotMaskSessionTimeout() {
        assertThat(masker.isSecret("server.servlet.session.timeout")).isFalse();
    }

    // -------------------------------------------------------------------------
    // Null and blank values
    // -------------------------------------------------------------------------

    @Test
    void nullValueRemainsNullForSecretKey() {
        assertThat(masker.mask("spring.datasource.password", null)).isNull();
    }

    @Test
    void nullValueRemainsNullForNonSecretKey() {
        assertThat(masker.mask("server.port", null)).isNull();
    }

    // -------------------------------------------------------------------------
    // Masked value format
    // -------------------------------------------------------------------------

    @Test
    void maskedValueIsExactlySixStars() {
        assertThat(SecretMasker.MASKED_VALUE).isEqualTo("******");
    }

    @Test
    void maskReturnsExactMaskedConstant() {
        Object result = masker.mask("db.password", "hunter2");
        assertThat(result).isSameAs(SecretMasker.MASKED_VALUE);
    }

    // -------------------------------------------------------------------------
    // Case-insensitivity (critical for browser-visible surface)
    // -------------------------------------------------------------------------

    @Test
    void caseInsensitivePasswordDetection() {
        assertThat(masker.isSecret("DB_PASSWORD")).isTrue();
        assertThat(masker.isSecret("Db.Password")).isTrue();
    }

    @Test
    void caseInsensitiveTokenDetection() {
        assertThat(masker.isSecret("ACCESS_TOKEN")).isTrue();
    }

    @Test
    void caseInsensitiveSecretDetection() {
        assertThat(masker.isSecret("MY_SECRET")).isTrue();
    }
}
