package io.github.jdubois.bootui.autoconfigure.safety;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

class ServletPeerAddressTests {

    @Test
    void unwrapsFrameworkHeadersButPrefersConnectorPeerOverNativeRewriting() {
        MockHttpServletRequest nativeRequest = new MockHttpServletRequest();
        nativeRequest.setRemoteAddr("127.0.0.1");
        nativeRequest.setAttribute(ServletPeerAddress.ATTRIBUTE, "192.0.2.10");
        var wrapped = new HttpServletRequestWrapper(nativeRequest) {
            @Override
            public String getRemoteAddr() {
                return "::1";
            }
        };
        assertThat(new ServletPeerAddress(true).remoteAddress(wrapped)).isEqualTo("192.0.2.10");
    }

    @Test
    void unknownNativeTransportFailsClosedWhileOrdinaryServletTransportStillWorks() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        assertThat(new ServletPeerAddress(true).remoteAddress(request)).isNull();
        assertThat(new ServletPeerAddress(false).remoteAddress(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void cloudDefaultIsNativeUnlessExplicitlyOverridden() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.main.cloud-platform", "HEROKU");
        assertThat(NativeForwarding.enabled(environment)).isTrue();
        environment.setProperty("server.forward-headers-strategy", "FRAMEWORK");
        assertThat(NativeForwarding.enabled(environment)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NATIVE", " native "})
    void nativeStrategyRequiresAVerifiedPeerDespiteSurroundingWhitespace(String strategy) {
        MockEnvironment environment = new MockEnvironment().withProperty("server.forward-headers-strategy", strategy);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        assertThat(new ServletPeerAddress(NativeForwarding.enabled(environment)).remoteAddress(request))
                .isNull();
    }
}
