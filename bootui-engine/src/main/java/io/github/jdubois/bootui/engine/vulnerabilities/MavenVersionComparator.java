package io.github.jdubois.bootui.engine.vulnerabilities;

import java.math.BigInteger;

/**
 * Minimal version comparator for Maven-style dotted version strings (e.g. {@code "2.17.1"}), used only to
 * derive {@link DependencyReports#fixAvailable(String, java.util.List)} &mdash; whether at least one of an
 * advisory's {@code fixedVersions} is newer than the dependency's currently-resolved version.
 *
 * <p>This is deliberately lightweight rather than a full implementation of Maven's own version-ordering
 * rules (as in {@code org.apache.maven.artifact.versioning.ComparableVersion}, which BootUI does not
 * depend on): each version is split on non-alphanumeric separators (typically {@code .} and {@code -}), and
 * same-position segments are compared numerically when both are all-digit, falling back to a
 * case-insensitive string compare otherwise, with a missing trailing segment treated as smaller (so
 * {@code "1.2"} sorts below {@code "1.2.1"}). This handles the common case of plain dotted-numeric Maven
 * versions correctly &mdash; including the classic lexical-compare bug where {@code "1.9"} would otherwise
 * look "greater" than {@code "1.10"} &mdash; without attempting qualifier/pre-release precedence
 * (`-alpha`/`-rc`/`-SNAPSHOT` ordering, etc).
 */
final class MavenVersionComparator {

    private MavenVersionComparator() {}

    /**
     * @return a negative number, zero, or a positive number as {@code left} is older, equal to, or newer
     *     than {@code right}; {@code null} if either version is blank (comparison not possible)
     */
    static Integer compare(String left, String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return null;
        }
        String[] leftParts = left.trim().split("[.\\-_]");
        String[] rightParts = right.trim().split("[.\\-_]");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            String leftPart = i < leftParts.length ? leftParts[i] : "";
            String rightPart = i < rightParts.length ? rightParts[i] : "";
            int compared = compareSegment(leftPart, rightPart);
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    private static int compareSegment(String left, String right) {
        boolean leftNumeric = isNumeric(left);
        boolean rightNumeric = isNumeric(right);
        if (leftNumeric && rightNumeric) {
            return new BigInteger(left).compareTo(new BigInteger(right));
        }
        if (leftNumeric != rightNumeric) {
            // A present numeric segment outranks a missing/non-numeric one at the same position -- this is
            // what makes a shorter version (e.g. "1.2", trailing segment absent) sort below "1.2.1".
            return leftNumeric ? 1 : -1;
        }
        return left.compareToIgnoreCase(right);
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
