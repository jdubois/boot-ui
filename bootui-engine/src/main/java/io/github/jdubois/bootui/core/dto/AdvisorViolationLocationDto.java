package io.github.jdubois.bootui.core.dto;

import java.util.Set;

/**
 * The one code element an advisor finding points at, when its scan evidence names exactly one.
 *
 * <p>Values are normalized on construction so no location can carry unbounded or control-character text:
 * an over-long or unsafe optional value becomes {@code null} rather than a truncated, wrong value, a line of
 * zero or less (ArchUnit's "unknown") is discarded, and {@code precision} is derived from what remains.</p>
 *
 * @param className the JVM binary class name, for example {@code com.example.Outer$Inner}
 * @param memberName the member's JVM name ({@code <init>} for a constructor), or {@code null} for the class itself
 * @param kind {@code CLASS}, {@code METHOD}, {@code CONSTRUCTOR}, or {@code FIELD}
 * @param sourceFile the source file name recorded in the class file, or {@code null} when none was recorded
 * @param line a positive line in {@code sourceFile}, or {@code null} when unknown or unverifiable
 * @param sourcePath the absolute local source path resolved during an explicit scan, or {@code null}
 * @param precision {@code LINE} when a line is known, {@code MEMBER} when only the member is, otherwise
 *     {@code CLASS}
 */
public record AdvisorViolationLocationDto(
        String className,
        String memberName,
        String kind,
        String sourceFile,
        Integer line,
        String sourcePath,
        String precision) {

    public static final String CLASS = "CLASS";
    public static final String METHOD = "METHOD";
    public static final String CONSTRUCTOR = "CONSTRUCTOR";
    public static final String FIELD = "FIELD";

    public static final String PRECISION_LINE = "LINE";
    public static final String PRECISION_MEMBER = "MEMBER";
    public static final String PRECISION_CLASS = "CLASS";

    public static final int MAX_NAME_LENGTH = 512;
    public static final int MAX_SOURCE_FILE_LENGTH = 255;
    public static final int MAX_PATH_LENGTH = 1024;

    private static final Set<String> MEMBER_KINDS = Set.of(METHOD, CONSTRUCTOR, FIELD);

    public AdvisorViolationLocationDto {
        className = bounded(className, MAX_NAME_LENGTH);
        if (className == null) {
            throw new IllegalArgumentException("Advisor violation location requires a class name.");
        }
        memberName = bounded(memberName, MAX_NAME_LENGTH);
        if (memberName == null || !MEMBER_KINDS.contains(kind)) {
            memberName = null;
            kind = CLASS;
        }
        sourceFile = bounded(sourceFile, MAX_SOURCE_FILE_LENGTH);
        if (sourceFile != null && (sourceFile.indexOf('/') >= 0 || sourceFile.indexOf('\\') >= 0)) {
            sourceFile = null;
        }
        if (line != null && line <= 0) {
            line = null;
        }
        sourcePath = bounded(sourcePath, MAX_PATH_LENGTH);
        precision = line != null ? PRECISION_LINE : memberName != null ? PRECISION_MEMBER : PRECISION_CLASS;
    }

    public AdvisorViolationLocationDto(
            String className, String memberName, String kind, String sourceFile, Integer line, String sourcePath) {
        this(className, memberName, kind, sourceFile, line, sourcePath, null);
    }

    /** This location with a resolved local source path, or without one when {@code sourcePath} is {@code null}. */
    public AdvisorViolationLocationDto withSourcePath(String sourcePath) {
        return new AdvisorViolationLocationDto(className, memberName, kind, sourceFile, line, sourcePath);
    }

    /** This location without its line, for a line that could not be verified against its source file. */
    public AdvisorViolationLocationDto withoutLine() {
        return new AdvisorViolationLocationDto(className, memberName, kind, sourceFile, null, sourcePath);
    }

    private static String bounded(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.isEmpty() || stripped.length() > maxLength) {
            return null;
        }
        for (int index = 0; index < stripped.length(); index++) {
            if (Character.isISOControl(stripped.charAt(index))) {
                return null;
            }
        }
        return stripped;
    }
}
