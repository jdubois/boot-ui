package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.github.jdubois.bootui.autoconfigure.safety.ServletPeerAddress;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class BootUiTomcatPeerAutoConfigurationTests {

    @Test
    void absentTomcatDoesNotLinkOptionalServerTypes() {
        new WebApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader("org.apache.catalina", "org.springframework.boot.tomcat"))
                .withConfiguration(AutoConfigurations.of(BootUiTomcatPeerAutoConfiguration.class))
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("bootUiTomcatPeerCustomizer");
                });
    }

    @Test
    void snapshotsConnectorPeerRatherThanForwardedRemoteAddress() throws Exception {
        var valve = new BootUiTomcatPeerAutoConfiguration.PeerValve();
        Request request = mock(Request.class);
        Response response = mock(Response.class);
        Valve next = mock(Valve.class);
        valve.setNext(next);
        when(request.getPeerAddr()).thenReturn("192.0.2.10");

        valve.invoke(request, response);

        verify(request).setAttribute(ServletPeerAddress.ATTRIBUTE, "192.0.2.10");
        verify(request, never()).getRemoteAddr();
        verify(next).invoke(request, response);
    }
}
