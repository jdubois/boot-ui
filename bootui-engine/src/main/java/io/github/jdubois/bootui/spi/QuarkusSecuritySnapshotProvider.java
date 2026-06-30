package io.github.jdubois.bootui.spi;

/**
 * Supplies a framework-neutral {@link QuarkusSecuritySnapshot} for the Quarkus Security advisor. The
 * adapter implements this over MicroProfile config + build-time annotation counts; the engine scanner
 * reads it once per scan and never sees a framework type.
 */
public interface QuarkusSecuritySnapshotProvider {

    QuarkusSecuritySnapshot snapshot();
}
