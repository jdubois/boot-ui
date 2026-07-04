package io.github.jdubois.bootui.engine.restapi.jaxrs.serverexceptionmapper;

import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * RESTEasy Reactive's simpler, {@code @Provider}-free exception-mapper style — a plain method
 * annotated {@code @ServerExceptionMapper}, the now-standard idiom on Quarkus (see the Quarkus REST
 * guide's "Custom exception mappers" section: https://quarkus.io/guides/rest#exception-mapping). This
 * class deliberately lives in its own package with NO classic {@code @Provider ExceptionMapper<X>}
 * alongside it, so a scan of just this package proves RAPI-ERR-001 no longer false-flags an app that
 * only uses this style.
 */
public class WidgetServerExceptionMapper {

    @ServerExceptionMapper
    public Response mapIllegalState(IllegalStateException exception) {
        return Response.serverError().build();
    }
}
