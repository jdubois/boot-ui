package io.github.jdubois.bootui.engine.restapi.newrules.responsecontracts.jaxrs;

import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

public class JaxRsRawStringMapper {

    @ServerExceptionMapper
    public String handle(IllegalArgumentException exception) {
        return exception.getMessage();
    }
}
