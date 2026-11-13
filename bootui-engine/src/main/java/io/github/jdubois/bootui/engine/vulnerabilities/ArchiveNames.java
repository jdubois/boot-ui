package io.github.jdubois.bootui.engine.vulnerabilities;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * File-name policy for attributing a classpath JAR archive to a resolved Maven coordinate.
 *
 * <p>The dependency inventory resolves coordinates from Maven descriptors and the application's SBOM, but
 * those sources say nothing about <em>which</em> archives they failed to cover. Comparing the resolved
 * coordinates against the real archive census is what turns "201 packages scanned" into "201 of 325 JARs
 * identified", so the panel can never present a partial scan as full coverage.</p>
 *
 * <p>Maven's repository layout names an artifact {@code <artifactId>-<version>[-<classifier>].jar}, which is
 * also how Spring Boot's repackaging writes {@code BOOT-INF/lib/} entries. That is a naming convention
 * rather than a guarantee, so it is used only for attribution &mdash; an archive that fails to match is
 * reported as unidentified, never silently dropped.</p>
 */
public final class ArchiveNames {

    private static final String JAR_SUFFIX = ".jar";

    private static final Pattern VERSION_QUALIFIER = Pattern.compile(
            "^(?:(?:alpha|beta|milestone|rc|cr|sp)(?:[.-]?\\d+)?|snapshot|ga|final|release|[abm]\\d+|\\d{8}\\.\\d{6}-\\d+)(?:[-.].*)?$",
            Pattern.CASE_INSENSITIVE);

    private ArchiveNames() {}

    private static boolean isJar(String name) {
        return name != null
                && name.length() > JAR_SUFFIX.length()
                && name.toLowerCase(Locale.ROOT).endsWith(JAR_SUFFIX);
    }

    /**
     * Returns the bare file name of a JAR path or archive entry name, or {@code null} when the path does not
     * name a JAR.
     *
     * <p>Handles both {@code /} and the platform separator, so it works on a {@code java.class.path} entry
     * and on a {@code BOOT-INF/lib/...} archive entry alike.</p>
     */
    public static String jarFileName(String path) {
        if (path == null) {
            return null;
        }
        String value = path.trim();
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        String name = slash < 0 ? value : value.substring(slash + 1);
        return isJar(name) ? name : null;
    }

    /**
     * Whether {@code archiveFileName} is the archive Maven would publish for {@code artifactId}/
     * {@code version}, either as the main artifact or as one of its classified artifacts.
     *
     * @param archiveFileName a bare JAR file name, as returned by {@link #jarFileName(String)}
     */
    public static boolean matches(String archiveFileName, String artifactId, String version) {
        if (archiveFileName == null || artifactId == null || version == null) {
            return false;
        }
        if (artifactId.isBlank() || version.isBlank()) {
            return false;
        }
        String base = artifactId + "-" + version;
        if (archiveFileName.equalsIgnoreCase(base + JAR_SUFFIX)) {
            return true;
        }
        // artifactId-version-classifier.jar; the classifier must be non-empty and not a pre-release version suffix.
        String classified = base + "-";
        if (archiveFileName.length() <= classified.length() + JAR_SUFFIX.length()
                || !archiveFileName.regionMatches(true, 0, classified, 0, classified.length())
                || !isJar(archiveFileName)) {
            return false;
        }
        String classifier =
                archiveFileName.substring(classified.length(), archiveFileName.length() - JAR_SUFFIX.length());
        return !classifier.isBlank()
                && classifier.charAt(0) != '-'
                && classifier.charAt(0) != '.'
                && !VERSION_QUALIFIER.matcher(classifier).matches();
    }
}
