package io.github.jdubois.bootui.spi;

/**
 * One bounded, framework-exposed gRPC setting carried as a neutral name/value pair.
 *
 * <p>Adapters use this for the settings whose key set genuinely differs between Spring gRPC and Quarkus gRPC
 * (keepalive timings, compression, name resolvers, and similar) so the shared DTO does not have to grow a
 * union of every framework field. Values must already be display-safe: never a credential, a raw connection
 * string, or TLS key/trust material.</p>
 *
 * @param name display name of the setting
 * @param value display value of the setting
 */
public record GrpcSetting(String name, String value) {}
