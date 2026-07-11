package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.engine.safety.ApiTokenAuthenticator;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

/** Authenticates non-loopback requests to the BootUI API. */
@ApplicationScoped
public class BootUiQuarkusAuthenticationFilter {

    private static final String API_PATH = "/bootui/api";
    private static final String SESSION_PATH = API_PATH + "/auth/session";
    private static final int PRIORITY = 975;

    private final Config config;
    private final ApiTokenAuthenticator authenticator;

    @Inject
    public BootUiQuarkusAuthenticationFilter(Config config, ApiTokenAuthenticator authenticator) {
        this.config = config;
        this.authenticator = authenticator;
    }

    public void register(@Observes Filters filters) {
        filters.register(this::handle, PRIORITY);
    }

    void handle(RoutingContext rc) {
        String path = relativePath(rc.normalizedPath());
        if (!isApiPath(path)) {
            rc.next();
            return;
        }

        HttpServerRequest request = rc.request();
        String remoteAddress = remoteAddress(request.remoteAddress());
        if (!authenticator.isAuthorized(
                remoteAddress, request.getHeader("Authorization"), request.getHeader("Cookie"))) {
            reject(rc.response());
            return;
        }

        if ("POST".equals(request.method().name()) && SESSION_PATH.equals(path)) {
            if (!authenticator.isLoopback(remoteAddress)) {
                rc.response()
                        .putHeader(
                                "Set-Cookie",
                                ApiTokenAuthenticator.SESSION_COOKIE_NAME
                                        + "="
                                        + authenticator.token()
                                        + "; Path="
                                        + API_PATH
                                        + "; HttpOnly; SameSite=Strict"
                                        + (request.isSSL() ? "; Secure" : ""));
            }
            rc.response().setStatusCode(204).end();
            return;
        }

        rc.next();
    }

    private String relativePath(String path) {
        String rootPath = config.getOptionalValue(QuarkusRootPath.ROOT_PATH_KEY, String.class)
                .orElse("/");
        return QuarkusRootPath.stripPrefix(path, QuarkusRootPath.normalize(rootPath));
    }

    private static boolean isApiPath(String path) {
        return path != null && (path.equals(API_PATH) || path.startsWith(API_PATH + "/"));
    }

    private static String remoteAddress(SocketAddress address) {
        return address == null ? null : address.hostAddress();
    }

    private static void reject(HttpServerResponse response) {
        response.setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .putHeader("Cache-Control", "no-store")
                .putHeader("WWW-Authenticate", ApiTokenAuthenticator.AUTHENTICATION_CHALLENGE)
                .end("{\"error\":\"" + ApiTokenAuthenticator.AUTHENTICATION_REQUIRED_MESSAGE + "\"}");
    }
}
