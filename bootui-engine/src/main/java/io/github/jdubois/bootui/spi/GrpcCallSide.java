package io.github.jdubois.bootui.spi;

/** Whether a gRPC metric sample was recorded on the server side or the client side of a call. */
public enum GrpcCallSide {
    SERVER,
    CLIENT
}
