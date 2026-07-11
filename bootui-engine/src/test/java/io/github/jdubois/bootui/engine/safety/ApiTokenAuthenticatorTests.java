package io.github.jdubois.bootui.engine.safety;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class ApiTokenAuthenticatorTests {

    private static final String TOKEN = "test-token";

    @Test
    void trustedSourcesDoNotRequireAuthentication() {
        ApiTokenAuthenticator authenticator = new ApiTokenAuthenticator(TOKEN);

        assertThat(authenticator.isAuthorized(true, null, null)).isTrue();
    }

    @Test
    void untrustedSourcesRequireTheBearerTokenOrSessionCookie() {
        ApiTokenAuthenticator authenticator = new ApiTokenAuthenticator(TOKEN);

        assertThat(authenticator.isAuthorized(false, null, null)).isFalse();
        assertThat(authenticator.isAuthorized(false, "invalid", null)).isFalse();
        assertThat(authenticator.isAuthorized(false, "Bearer " + TOKEN, null)).isTrue();
        assertThat(authenticator.isAuthorized(
                        false, null, "other=value; " + ApiTokenAuthenticator.SESSION_COOKIE_NAME + "=" + TOKEN))
                .isTrue();
    }

    @Test
    void generatesAUrlSafe256BitTokenWhenNoneIsConfigured() {
        ApiTokenAuthenticator authenticator = new ApiTokenAuthenticator(null, new SecureRandom(new byte[] {1, 2, 3}));

        assertThat(authenticator.generated()).isTrue();
        assertThat(authenticator.token()).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void configuredTokensAreNotMarkedAsGenerated() {
        ApiTokenAuthenticator authenticator = new ApiTokenAuthenticator(TOKEN);

        assertThat(authenticator.generated()).isFalse();
        assertThat(authenticator.token()).isEqualTo(TOKEN);
    }
}
