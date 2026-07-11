package io.github.jdubois.bootui.engine.safety;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class ApiTokenAuthenticatorTests {

    private static final String TOKEN = "test-token";

    @Test
    void loopbackRequestsDoNotRequireAuthentication() {
        ApiTokenAuthenticator authenticator = new ApiTokenAuthenticator(TOKEN);

        assertThat(authenticator.isAuthorized("127.0.0.1", null, null)).isTrue();
        assertThat(authenticator.isAuthorized("::1", null, null)).isTrue();
    }

    @Test
    void nonLoopbackRequestsRequireTheBearerTokenOrSessionCookie() {
        ApiTokenAuthenticator authenticator = new ApiTokenAuthenticator(TOKEN);

        assertThat(authenticator.isAuthorized("10.0.0.5", null, null)).isFalse();
        assertThat(authenticator.isAuthorized("10.0.0.5", "invalid", null)).isFalse();
        assertThat(authenticator.isAuthorized("10.0.0.5", "Bearer " + TOKEN, null))
                .isTrue();
        assertThat(authenticator.isAuthorized(
                        "10.0.0.5", null, "other=value; " + ApiTokenAuthenticator.SESSION_COOKIE_NAME + "=" + TOKEN))
                .isTrue();
    }

    @Test
    void malformedAddressesFailClosed() {
        ApiTokenAuthenticator authenticator = new ApiTokenAuthenticator(TOKEN);

        assertThat(authenticator.isAuthorized(null, null, null)).isFalse();
        assertThat(authenticator.isAuthorized("", null, null)).isFalse();
        assertThat(authenticator.isAuthorized("not-an-address", null, null)).isFalse();
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
