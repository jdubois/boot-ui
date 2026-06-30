package io.github.jdubois.bootui.spi;

/**
 * Marker interface for BootUI service provider interfaces.
 *
 * <p>It declares no methods. It exists so the {@code io.github.jdubois.bootui.spi} module always
 * compiles to at least one type (keeping source/javadoc packaging and ArchUnit analysis well defined)
 * and to give the SPI package a single documented anchor. Concrete SPIs are introduced just-in-time as
 * each vertical slice is extracted from the Spring adapter; they may, but are not required to, extend
 * this marker.
 */
public interface BootUiSpi {}
