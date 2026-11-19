package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.safety.ServletPeerAddress;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Conditional(BootUiActivationCondition.class)
public class BootUiTomcatPeerAutoConfiguration {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> bootUiTomcatPeerCustomizer() {
        return factory -> factory.addContextValves(new PeerValve());
    }

    static final class PeerValve extends ValveBase {

        PeerValve() {
            super(true);
        }

        @Override
        public void invoke(Request request, Response response) throws IOException, ServletException {
            // Unlike getRemoteAddr(), getPeerAddr() is not rewritten by RemoteIpValve or PROXY protocol.
            request.setAttribute(ServletPeerAddress.ATTRIBUTE, request.getPeerAddr());
            getNext().invoke(request, response);
        }
    }
}
