package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * The database server version reported by {@code DatabaseMetaData}, used to gate vendor catalog columns
 * that only exist from a given release on ({@code information_schema.statistics.IS_VISIBLE} on MySQL 8.0,
 * {@code IGNORED} on MariaDB 10.6, {@code pg_sequences} and declarative partitioning on PostgreSQL 10).
 *
 * @param major the major version, or {@code -1} when the driver could not report it
 * @param minor the minor version, or {@code -1} when the driver could not report it
 * @param patch the patch level parsed from the version string, or {@code -1} when it is not parseable
 * @param text the raw product version string, or {@code null}
 */
public record DatabaseVersion(int major, int minor, int patch, String text) {

    public static final DatabaseVersion UNKNOWN = new DatabaseVersion(-1, -1, -1, null);

    public boolean known() {
        return major >= 0;
    }

    /** True when this version is at least {@code major.minor}; {@code false} when the version is unknown. */
    public boolean atLeast(int requiredMajor, int requiredMinor) {
        if (!known()) {
            return false;
        }
        if (major != requiredMajor) {
            return major > requiredMajor;
        }
        return minor >= requiredMinor;
    }

    /** True when this version is at least {@code major.minor.patch}; {@code false} when unknown. */
    public boolean atLeast(int requiredMajor, int requiredMinor, int requiredPatch) {
        if (!atLeast(requiredMajor, requiredMinor)) {
            return false;
        }
        if (major > requiredMajor || minor > requiredMinor) {
            return true;
        }
        return patch >= requiredPatch;
    }

    public String describe() {
        return text == null || text.isBlank() ? "unknown version" : text;
    }

    /**
     * Builds a version from the driver's numeric accessors, using the raw version string only to recover a
     * patch level (drivers expose no {@code getDatabasePatchVersion()}).
     */
    public static DatabaseVersion of(int major, int minor, String text) {
        return new DatabaseVersion(major, minor, parsePatch(text, major, minor), text);
    }

    private static int parsePatch(String text, int major, int minor) {
        if (text == null || major < 0 || minor < 0) {
            return -1;
        }
        String prefix = major + "." + minor + ".";
        int start = text.indexOf(prefix);
        if (start < 0) {
            return -1;
        }
        int cursor = start + prefix.length();
        int end = cursor;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        if (end == cursor) {
            return -1;
        }
        try {
            return Integer.parseInt(text.substring(cursor, end));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}
