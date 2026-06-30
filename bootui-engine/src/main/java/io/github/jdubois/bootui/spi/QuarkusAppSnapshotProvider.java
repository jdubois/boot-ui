package io.github.jdubois.bootui.spi;

/**
 * Supplies a framework-neutral {@link QuarkusAppSnapshot} for the Quarkus application advisor. The adapter
 * implements this over MicroProfile config + build-time annotation counts; the engine scanner reads it once
 * per scan and never sees a framework type. The Quarkus counterpart to the Spring advisor's application-context
 * inspection.
 */
public interface QuarkusAppSnapshotProvider {

    QuarkusAppSnapshot snapshot();
}
