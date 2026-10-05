/**
 * A framework-free Java client for BootUI's command-line endpoint.
 *
 * <p>Depends on nothing but the JDK, deliberately: it is meant to be embedded in CLIs and build plugins,
 * where an extra JSON library on the classpath is a version conflict waiting to happen, and it must stay
 * reflection-free so a native-image build remains a build-file change rather than a rewrite.
 *
 * <p>It does not depend on {@code bootui-core} either. Tool payloads are opaque JSON, passed through as
 * received, so a client built against one BootUI version keeps working against an application running
 * another.
 */
package io.github.jdubois.bootui.client;
