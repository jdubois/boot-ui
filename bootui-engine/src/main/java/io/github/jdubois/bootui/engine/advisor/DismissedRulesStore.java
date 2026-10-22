package io.github.jdubois.bootui.engine.advisor;

import io.github.jdubois.bootui.core.dto.DismissedFindingDto;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads and writes what the developer dismissed in the advisors, stored under two sibling nodes of BootUI's generic
 * {@code boot-ui.yml} file: {@code dismissedRules} (whole rules) and {@code dismissedFindings} (single findings).
 *
 * <p>The file lives under the {@code .bootui/} directory (e.g.
 * {@code .bootui/boot-ui.yml}) and is intended to be git-ignored. It is a
 * generic BootUI configuration file that can hold other top-level sections; this
 * store only owns the two nodes:</p>
 *
 * <pre>
 * # BootUI configuration (developer-local; safe to delete).
 * dismissedRules:
 *   - SPRING-001
 *   - MEM-003
 * dismissedFindings:
 *   - HIB-ID-006 v1:unit=app|entity=com.acme.Item|member=id
 *   - HIB-MAP-006 v1:unit=app|entity=com.acme.Order|member=customer n=SW50ZW50aW9uYWw
 * </pre>
 *
 * <p>A finding entry is {@code <ruleId> <identity> [n=<note>]}, single-space separated. The rule id and the canonical
 * identity ({@link AdvisorFindingIdentity}) contain no whitespace, and the optional note is the Base64URL encoding
 * (no padding) of at most {@value #MAX_NOTE_LENGTH} characters of UTF-8 text. User text therefore never needs YAML
 * escaping, and a malformed or hand-edited line can neither corrupt the file nor be mistaken for another entry.
 * Entries that do not match this format exactly are ignored when reading, counted in {@link #loadFindings()}'s
 * {@code ignored} total and never echoed, and they are kept verbatim when the file is rewritten, so a format a newer
 * BootUI wrote is not destroyed by an older one.</p>
 *
 * <p>Any other top-level sections present in the file are preserved across writes. The two managed sections are
 * rewritten and emitted last, so a comment placed immediately after one (with no intervening top-level key) is
 * treated as part of the managed section and not preserved. A BootUI that does not know {@code dismissedFindings}
 * treats it as an unrelated section and preserves it.</p>
 *
 * <p>This class never uses a third-party YAML library so that it imposes no
 * additional classpath dependency on {@code bootui-spring-boot-starter}. It therefore
 * understands only the minimal block format it writes: a top-level key followed by two-space-indented
 * {@code - <entry>} list items. Inline comments or quoted scalars on those items are not interpreted.</p>
 */
public class DismissedRulesStore {

    /** The most dismissed findings the store keeps; a further one is refused with 409, replacing a note is not. */
    public static final int MAX_FINDINGS = 1000;

    /** The longest note, in characters of the text before encoding. */
    public static final int MAX_NOTE_LENGTH = 200;

    private static final String KEY = "dismissedRules";

    private static final String FINDINGS_KEY = "dismissedFindings";

    private static final String HEADER = "# BootUI configuration (developer-local; safe to delete).";

    private static final String ITEM = "  - ";

    private static final Pattern RULE_ID = Pattern.compile("[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+");

    private static final int MAX_RULE_ID_LENGTH = 64;

    private static final Pattern IDENTITY = Pattern.compile("v[0-9]{1,3}:" + AdvisorFindingIdentity.VALID.pattern());

    private static final String NOTE_PREFIX = "n=";

    private final Path file;

    public DismissedRulesStore(Path file) {
        this.file = file;
    }

    /**
     * Loads the current set of dismissed rule IDs from disk. Returns an empty set
     * if the file does not yet exist.
     */
    public synchronized Set<String> load() {
        return read().rules;
    }

    /**
     * Adds a rule ID to the dismissed set and persists the updated set to disk.
     * Returns the updated set. Does nothing if the rule is already dismissed.
     */
    public synchronized Set<String> dismiss(String ruleId) {
        rejectControlCharacters(ruleId);
        Contents contents = read();
        if (contents.rules.add(ruleId)) {
            save(contents);
        }
        return contents.rules;
    }

    /**
     * Removes a rule ID from the dismissed set and persists the updated set to disk.
     * Returns the updated set. Does nothing if the rule was not dismissed.
     */
    public synchronized Set<String> restore(String ruleId) {
        Contents contents = read();
        if (contents.rules.remove(ruleId)) {
            save(contents);
        }
        return contents.rules;
    }

    /** What is dismissed one finding at a time, in the order it was dismissed. */
    public synchronized Findings loadFindings() {
        Contents contents = read();
        return new Findings(List.copyOf(contents.findings.values()), contents.ignored.size());
    }

    /** The dismissed rules and findings from one read of the file, so a report is projected from one state. */
    public synchronized Snapshot snapshot() {
        Contents contents = read();
        return new Snapshot(
                Set.copyOf(contents.rules), List.copyOf(contents.findings.values()), contents.ignored.size());
    }

    /**
     * Dismisses one finding, or replaces the note of one already dismissed.
     *
     * @param note optional free text of at most {@value #MAX_NOTE_LENGTH} characters; a blank note means none
     * @throws AdvisorViolationException with status 400 for an invalid rule id, identity or note, and 409 when
     *     {@value #MAX_FINDINGS} findings are already dismissed
     */
    public synchronized Findings dismissFinding(String ruleId, String identity, String note) {
        validate(ruleId, identity);
        String normalizedNote = note == null || note.isBlank() ? null : note;
        if (normalizedNote != null) {
            encodeNote(normalizedNote);
        }
        Contents contents = read();
        String key = key(ruleId, identity);
        DismissedFindingDto existing = contents.findings.get(key);
        if (existing == null && contents.findings.size() >= MAX_FINDINGS) {
            throw new AdvisorViolationException(
                    409, "Too many dismissed findings: restore some before dismissing more.");
        }
        DismissedFindingDto updated = new DismissedFindingDto(ruleId, identity, normalizedNote);
        if (!updated.equals(existing)) {
            contents.findings.put(key, updated);
            save(contents);
        }
        return new Findings(List.copyOf(contents.findings.values()), contents.ignored.size());
    }

    /** Restores one dismissed finding. Does nothing if it was not dismissed. */
    public synchronized Findings restoreFinding(String ruleId, String identity) {
        validate(ruleId, identity);
        Contents contents = read();
        if (contents.findings.remove(key(ruleId, identity)) != null) {
            save(contents);
        }
        return new Findings(List.copyOf(contents.findings.values()), contents.ignored.size());
    }

    /** The dismissed findings and how many stored entries were ignored as unreadable. */
    public record Findings(List<DismissedFindingDto> findings, int ignored) {}

    /** The dismissed rules and findings read together. */
    public record Snapshot(Set<String> rules, List<DismissedFindingDto> findings, int ignored) {}

    private static void validate(String ruleId, String identity) {
        if (ruleId == null
                || ruleId.length() > MAX_RULE_ID_LENGTH
                || !RULE_ID.matcher(ruleId).matches()) {
            throw new AdvisorViolationException(400, "Dismissed finding rule id is not a valid advisor rule id.");
        }
        if (identity == null
                || identity.length() > AdvisorFindingIdentity.MAX_LENGTH
                || !IDENTITY.matcher(identity).matches()) {
            throw new AdvisorViolationException(400, "Dismissed finding identity is not a valid finding identity.");
        }
    }

    /** A rule id becomes one line of the file, so a line break in it would forge entries or sections. */
    private static void rejectControlCharacters(String ruleId) {
        if (ruleId == null || ruleId.isBlank() || ruleId.chars().anyMatch(Character::isISOControl)) {
            throw new AdvisorViolationException(400, "Dismissed rule id must be a non-blank single line.");
        }
    }

    private static String key(String ruleId, String identity) {
        return ruleId + ' ' + identity;
    }

    private static String encodeNote(String note) {
        if (note.codePointCount(0, note.length()) > MAX_NOTE_LENGTH) {
            throw new AdvisorViolationException(
                    400, "Dismissed finding note must be at most " + MAX_NOTE_LENGTH + " characters.");
        }
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(note));
            byte[] raw = new byte[bytes.remaining()];
            bytes.get(raw);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (CharacterCodingException e) {
            throw new AdvisorViolationException(400, "Dismissed finding note is not valid text.");
        }
    }

    /** The note stored as {@code encoded}, or {@code null} unless it is exactly what {@link #encodeNote} writes. */
    private static String decodeNote(String encoded) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(encoded);
            String note = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
            if (note.isBlank()
                    || note.codePointCount(0, note.length()) > MAX_NOTE_LENGTH
                    || !encodeNote(note).equals(encoded)) {
                return null;
            }
            return note;
        } catch (IllegalArgumentException | CharacterCodingException | AdvisorViolationException e) {
            return null;
        }
    }

    /** The finding a stored line is, or {@code null} unless the line is exactly the format this store writes. */
    private static DismissedFindingDto parseFinding(String line) {
        if (!line.startsWith(ITEM)) {
            return null;
        }
        String[] parts = line.substring(ITEM.length()).split(" ", -1);
        if (parts.length < 2 || parts.length > 3) {
            return null;
        }
        if (Arrays.stream(parts).anyMatch(String::isEmpty)) {
            return null;
        }
        try {
            validate(parts[0], parts[1]);
        } catch (AdvisorViolationException e) {
            return null;
        }
        String note = null;
        if (parts.length == 3) {
            if (!parts[2].startsWith(NOTE_PREFIX)) {
                return null;
            }
            note = decodeNote(parts[2].substring(NOTE_PREFIX.length()));
            if (note == null) {
                return null;
            }
        }
        return new DismissedFindingDto(parts[0], parts[1], note);
    }

    private Contents read() {
        Contents contents = new Contents();
        if (!Files.exists(file)) {
            return contents;
        }
        try {
            String section = null;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (isTopLevelKey(line)) {
                    String key = keyOf(line);
                    section = key.equals(KEY) || key.equals(FINDINGS_KEY) ? key : null;
                    contents.hadRulesKey |= key.equals(KEY);
                    continue;
                }
                if (section == null) {
                    continue;
                }
                if (section.equals(FINDINGS_KEY)
                        && !line.isBlank()
                        && !line.startsWith("#")
                        && !line.startsWith(ITEM)) {
                    // Not a list item this version writes: kept as it is, so a newer format is not lost.
                    contents.ignored.add(line);
                    continue;
                }
                if (!line.startsWith(ITEM)) {
                    continue;
                }
                if (section.equals(KEY)) {
                    String id = line.substring(ITEM.length()).trim();
                    if (!id.isEmpty()) {
                        contents.rules.add(id);
                    }
                } else {
                    DismissedFindingDto finding = parseFinding(line);
                    if (finding == null) {
                        contents.ignored.add(line);
                    } else {
                        contents.findings.putIfAbsent(key(finding.ruleId(), finding.identity()), finding);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read BootUI configuration file: " + file, e);
        }
        return contents;
    }

    private void save(Contents contents) {
        try {
            List<String> preserved = preservedLines();
            StringBuilder sb = new StringBuilder();
            sb.append(HEADER).append('\n');
            if (!preserved.isEmpty()) {
                sb.append('\n');
            }
            for (String line : preserved) {
                sb.append(line).append('\n');
            }
            sb.append('\n');
            if (!contents.rules.isEmpty() || contents.hadRulesKey) {
                sb.append(KEY).append(":\n");
                for (String id : contents.rules) {
                    sb.append(ITEM).append(id).append('\n');
                }
            }
            if (!contents.findings.isEmpty() || !contents.ignored.isEmpty()) {
                sb.append(FINDINGS_KEY).append(":\n");
                for (DismissedFindingDto finding : contents.findings.values()) {
                    sb.append(ITEM).append(finding.ruleId()).append(' ').append(finding.identity());
                    if (finding.note() != null) {
                        sb.append(' ').append(NOTE_PREFIX).append(encodeNote(finding.note()));
                    }
                    sb.append('\n');
                }
                for (String line : contents.ignored) {
                    sb.append(line).append('\n');
                }
            }
            writeAtomically(sb.toString());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write BootUI configuration file: " + file, e);
        }
    }

    /**
     * Returns the file's existing lines with the managed sections and our own header comment removed, so other
     * top-level sections survive a rewrite while the header and sections are re-emitted exactly once. Leading and
     * trailing blank lines are trimmed so the separator before the rewritten sections stays stable across repeated
     * saves.
     */
    private List<String> preservedLines() throws IOException {
        List<String> preserved = new ArrayList<>();
        if (!Files.exists(file)) {
            return preserved;
        }
        boolean inSection = false;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.equals(HEADER)) {
                continue;
            }
            if (isTopLevelKey(line)) {
                String key = keyOf(line);
                inSection = key.equals(KEY) || key.equals(FINDINGS_KEY);
                if (!inSection) {
                    preserved.add(line);
                }
                continue;
            }
            if (!inSection) {
                preserved.add(line);
            }
        }
        while (!preserved.isEmpty() && preserved.get(0).isBlank()) {
            preserved.remove(0);
        }
        while (!preserved.isEmpty() && preserved.get(preserved.size() - 1).isBlank()) {
            preserved.remove(preserved.size() - 1);
        }
        return preserved;
    }

    private void writeAtomically(String content) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path tmp = (file.getParent() != null)
                ? Files.createTempFile(file.getParent(), "boot-ui", ".tmp")
                : Files.createTempFile("boot-ui", ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isTopLevelKey(String line) {
        if (line.isBlank()) {
            return false;
        }
        char first = line.charAt(0);
        if (Character.isWhitespace(first) || first == '#' || first == '-') {
            return false;
        }
        return line.indexOf(':') >= 0;
    }

    private static String keyOf(String line) {
        int idx = line.indexOf(':');
        return (idx >= 0 ? line.substring(0, idx) : line).trim();
    }

    /** One read of the file. */
    private static final class Contents {
        private final Set<String> rules = new LinkedHashSet<>();
        private final Map<String, DismissedFindingDto> findings = new LinkedHashMap<>();
        private final List<String> ignored = new ArrayList<>();
        private boolean hadRulesKey;
    }
}
