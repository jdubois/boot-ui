package io.github.jdubois.bootui.spi;

/** A declared REST-server route, not the result of executing an authorization policy. */
public record QuarkusSecurityEndpoint(String path, String method, Access access, boolean document) {
    public enum Access {
        UNANNOTATED,
        PERMIT,
        RESTRICTED,
        UNKNOWN
    }
}
