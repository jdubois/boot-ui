package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.Map;

/**
 * What the Database Advisor knows about the JDBC driver behind one datasource, without ever keeping the JDBC URL,
 * the user name or the password: the driver's self-reported name and version, and the allow-listed settings found in
 * the URL and in the pool's driver properties ({@link DriverSettingCatalog}).
 *
 * <p>An absent setting means <em>not configured where the advisor can see it</em>, which is a statement about the URL
 * and the pool properties only: the driver may still read it from a configuration file or an environment variable.
 * It is a statement at all only for a source that was read: {@link #urlSettingsKnown()} is false when the URL could
 * not be read, and {@link #poolSettingsKnown()} is false when the datasource's pool, and so its driver properties,
 * could not be identified or read. A rule must not treat the absence of a setting, or a value that another source
 * could override, as evidence while a source is unknown.</p>
 *
 * @param driverName {@code DatabaseMetaData.getDriverName()}, or {@code null} when it could not be read
 * @param driverVersion {@code DatabaseMetaData.getDriverVersion()}, or {@code null} when it could not be read
 * @param settings the configured allow-listed settings, keyed by canonical name
 * @param urlSettingsKnown whether the JDBC URL was read
 * @param poolSettingsKnown whether the pool's driver properties were read
 */
record DriverEvidence(
        String driverName,
        String driverVersion,
        Map<String, DriverSetting> settings,
        boolean urlSettingsKnown,
        boolean poolSettingsKnown) {

    DriverEvidence {
        settings = Map.copyOf(settings);
    }

    /** Evidence whose pool properties were read. */
    DriverEvidence(
            String driverName, String driverVersion, Map<String, DriverSetting> settings, boolean urlSettingsKnown) {
        this(driverName, driverVersion, settings, urlSettingsKnown, true);
    }

    /** Evidence whose URL and pool properties were both read, so an absent setting is a configured-nowhere fact. */
    DriverEvidence(String driverName, String driverVersion, Map<String, DriverSetting> settings) {
        this(driverName, driverVersion, settings, true, true);
    }

    DriverSettingCatalog.Driver driver() {
        return DriverSettingCatalog.Driver.of(driverName);
    }

    /** Whether a setting that can replace an explicit value (a Connector/J properties transform) is configured. */
    boolean overrideConfigured() {
        return DriverSettingCatalog.overrideMarkers(driver()).stream().anyMatch(settings::containsKey);
    }

    /** Whether a setting that makes the driver read more configuration from an unseen file is configured. */
    boolean expansionConfigured() {
        return DriverSettingCatalog.expansionMarkers(driver()).stream().anyMatch(settings::containsKey);
    }
}
