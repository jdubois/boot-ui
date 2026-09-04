package io.github.jdubois.bootui.engine.vulnerabilities;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Parses Maven coordinates out of a <a href="https://github.com/package-url/purl-spec">package URL</a>.
 *
 * <p>A CycloneDX SBOM identifies every component by {@code purl}, and for the Maven ecosystem that is
 * exactly {@code pkg:maven/<groupId>/<artifactId>@<version>} &mdash; the {@code groupId} that no JAR
 * manifest header carries. That makes an application's embedded SBOM the only local source able to identify
 * the many artifacts published without {@code META-INF/maven/.../pom.properties}.</p>
 *
 * <p>Parsing lives in the engine so every adapter that can read an SBOM applies the same rules; reading the
 * JSON itself stays in the adapters, which own a JSON library (the engine deliberately does not).</p>
 */
public final class PackageUrls {

    private static final String MAVEN_PREFIX = "pkg:maven/";

    private PackageUrls() {}

    /**
     * Parses a Maven package URL into its coordinates.
     *
     * <p>Per the purl specification the type is case-insensitive, the optional {@code ?qualifiers} and
     * {@code #subpath} segments are ignored here (BootUI scans by {@code groupId:artifactId:version}, so a
     * classifier or packaging qualifier does not change the OSV query), and percent-encoded characters are
     * decoded. Any purl of another type, or one missing a namespace, name, or version, yields {@code null}
     * rather than a half-populated coordinate.
     *
     * @param purl the package URL, possibly {@code null}
     * @return the coordinates, or {@code null} when {@code purl} is not a complete Maven package URL
     */
    public static MavenCoordinates mavenCoordinates(String purl) {
        if (purl == null) {
            return null;
        }
        String value = purl.trim();
        if (value.length() <= MAVEN_PREFIX.length()
                || !value.regionMatches(true, 0, MAVEN_PREFIX, 0, MAVEN_PREFIX.length())) {
            return null;
        }
        value = value.substring(MAVEN_PREFIX.length());
        value = stripAfter(stripAfter(value, '#'), '?');

        int at = value.lastIndexOf('@');
        if (at <= 0 || at == value.length() - 1) {
            return null;
        }
        String version = decode(value.substring(at + 1));
        String path = value.substring(0, at);

        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0 || lastSlash == path.length() - 1) {
            return null;
        }
        // A Maven namespace is a single segment, but decode defensively rather than assume it.
        String groupId = decode(path.substring(0, lastSlash).replace('/', '.'));
        String artifactId = decode(path.substring(lastSlash + 1));
        if (groupId.isEmpty() || artifactId.isEmpty() || version.isEmpty()) {
            return null;
        }
        return new MavenCoordinates(groupId, artifactId, version);
    }

    private static String stripAfter(String value, char delimiter) {
        int index = value.indexOf(delimiter);
        return index < 0 ? value : value.substring(0, index);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException ex) {
            // Malformed percent-encoding: keep the raw text rather than dropping the component entirely.
            return value.trim();
        }
    }

    /** A resolved {@code groupId:artifactId:version} triple. */
    public record MavenCoordinates(String groupId, String artifactId, String version) {

        public String packageName() {
            return groupId + ":" + artifactId;
        }
    }
}
