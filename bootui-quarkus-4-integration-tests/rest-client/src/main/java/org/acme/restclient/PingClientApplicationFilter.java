package org.acme.restclient;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;

/**
 * Application-owned filters used to prove BootUI composes with existing REST Client customization. The
 * request filter adds a header consumed by the target; the response filter rewrites one status after
 * BootUI's transport-bracketing response filter has observed it.
 */
@Priority(Priorities.USER)
public class PingClientApplicationFilter implements ClientRequestFilter, ClientResponseFilter {

    @Override
    public void filter(ClientRequestContext requestContext) {
        requestContext.getHeaders().putSingle("X-Application-Filter", "active");
    }

    @Override
    public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) {
        if (requestContext.getUri().getPath().endsWith("/filtered-status")) {
            responseContext.setStatus(200);
        }
    }
}
