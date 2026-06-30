package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * The raw configuration entries contributed by a single profile-specific property source.
 *
 * <p>Framework-neutral seam carrier behind the Profile Diff panel. The Spring adapter maps one
 * {@code application-<profile>.{properties,yml}} property source to one of these; the Quarkus adapter
 * groups the active {@code %profile.}-prefixed MicroProfile keys into one per active profile. The engine
 * masks the entries and assembles the {@code ProfileSourceDto} the UI binds to.
 */
public record ProfileSource(String sourceName, String profile, List<ConfigEntry> entries) {}
