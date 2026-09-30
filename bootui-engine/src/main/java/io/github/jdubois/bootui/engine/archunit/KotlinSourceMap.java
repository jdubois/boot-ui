package io.github.jdubois.bootui.engine.archunit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * The line mapping a Kotlin class file records in its SMAP ({@code SourceDebugExtension}, JSR-45).
 *
 * <p>When Kotlin inlines a function body it gives the copied instructions line numbers beyond the end of the
 * calling file, and records in the SMAP which file and line each range really came from. A line from such a
 * range belongs to another file, so it must never be shown as a line of the class's own source file.</p>
 */
final class KotlinSourceMap {

    private record Range(int fileId, int inputStart, int outputStart, int outputEnd, int increment) {}

    private final int declaringFileId;
    private final List<Range> ranges;

    private KotlinSourceMap(int declaringFileId, List<Range> ranges) {
        this.declaringFileId = declaringFileId;
        this.ranges = List.copyOf(ranges);
    }

    /**
     * Parses the default stratum of {@code smap}, taking the declaring file to be the one named
     * {@code sourceFile}. Returns {@code null} when the map is absent, malformed, or does not name that file.
     */
    static KotlinSourceMap parse(String smap, String sourceFile) {
        if (smap == null || sourceFile == null) return null;
        String[] lines = smap.split("\r?\n", -1);
        if (lines.length < 3 || !lines[0].equals("SMAP")) return null;
        String stratum = lines[2].strip();
        Map<Integer, String> files = new HashMap<>();
        List<Range> ranges = new ArrayList<>();
        String section = null;
        boolean inStratum = false;
        int lastFileId = 0;
        try {
            for (int index = 3; index < lines.length; index++) {
                String line = lines[index].strip();
                if (line.startsWith("*S ")) {
                    if (inStratum) break;
                    inStratum = line.substring(3).strip().equals(stratum);
                    section = null;
                    continue;
                }
                if (line.equals("*E")) break;
                if (!inStratum) continue;
                if (line.startsWith("*")) {
                    section = line;
                    continue;
                }
                if (line.isEmpty()) continue;
                if ("*F".equals(section)) {
                    boolean withPath = line.startsWith("+ ");
                    String entry = withPath ? line.substring(2).strip() : line;
                    int space = entry.indexOf(' ');
                    if (space < 0) return null;
                    files.put(
                            Integer.parseInt(entry.substring(0, space)),
                            entry.substring(space + 1).strip());
                    if (withPath) index++;
                } else if ("*L".equals(section)) {
                    int colon = line.indexOf(':');
                    if (colon < 0) return null;
                    String input = line.substring(0, colon);
                    String output = line.substring(colon + 1);
                    int repeat = 1;
                    int comma = input.indexOf(',');
                    if (comma >= 0) {
                        repeat = Integer.parseInt(input.substring(comma + 1));
                        input = input.substring(0, comma);
                    }
                    int hash = input.indexOf('#');
                    if (hash >= 0) {
                        lastFileId = Integer.parseInt(input.substring(hash + 1));
                        input = input.substring(0, hash);
                    }
                    int inputStart = Integer.parseInt(input);
                    int increment = 1;
                    int outputComma = output.indexOf(',');
                    if (outputComma >= 0) {
                        increment = Integer.parseInt(output.substring(outputComma + 1));
                        output = output.substring(0, outputComma);
                    }
                    int outputStart = Integer.parseInt(output);
                    if (repeat < 1 || increment < 1 || inputStart < 1 || outputStart < 1) return null;
                    long outputEnd = (long) outputStart + (long) repeat * increment - 1;
                    if (outputEnd > Integer.MAX_VALUE) return null;
                    ranges.add(new Range(lastFileId, inputStart, outputStart, (int) outputEnd, increment));
                }
            }
        } catch (NumberFormatException ex) {
            return null;
        }
        Integer declaring = files.getOrDefault(1, "").equals(sourceFile) ? Integer.valueOf(1) : null;
        if (declaring == null) {
            for (Map.Entry<Integer, String> file : files.entrySet()) {
                if (file.getValue().equals(sourceFile)) {
                    if (declaring != null) return null;
                    declaring = file.getKey();
                }
            }
        }
        return declaring == null ? null : new KotlinSourceMap(declaring, ranges);
    }

    /**
     * Whether output line {@code line} is the declaring file's own line, unchanged. Kotlin maps the class's own
     * code one-to-one and gives inlined code, even an inline function from the same file, lines past the end of
     * the file that map elsewhere, so only an identity mapping names the line of the member that holds it.
     */
    boolean isOwnLine(int line) {
        OptionalInt declaring = declaringLine(line);
        return declaring.isPresent() && declaring.getAsInt() == line;
    }

    /**
     * The line of the declaring file that output line {@code line} maps back to, or empty when the line came
     * from another file (inlined code) or is not mapped at all.
     */
    OptionalInt declaringLine(int line) {
        for (Range range : ranges) {
            if (line >= range.outputStart() && line <= range.outputEnd()) {
                if (range.fileId() != declaringFileId) return OptionalInt.empty();
                return OptionalInt.of(range.inputStart() + (line - range.outputStart()) / range.increment());
            }
        }
        return OptionalInt.empty();
    }
}
