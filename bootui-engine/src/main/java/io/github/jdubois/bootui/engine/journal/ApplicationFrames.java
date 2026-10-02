package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.support.StackFramePrefixes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Up to {@value #MAX_FRAMES} application frames above a captured operation, innermost first, such as the repository
 * method that ran a statement and the service and controller methods that called it ({@code docs/PLAN-v2.md} §5.2).
 * The first frame is the operation's call site. Each frame reads {@code ClassName.method(File.java:42)}, as call sites
 * and exception locations do.
 *
 * <p>The journal interns the frames in its run's dictionary when it retains the event, so a frame repeated by thousands
 * of events is stored once. {@link #estimatedBytes()} counts the frames the dictionary could not take.</p>
 *
 * @param frames the frames, innermost first
 * @param unsharedBytes the bytes of the frames this instance retains alone: all of them until interned
 */
public record ApplicationFrames(List<String> frames, int unsharedBytes) {

    /** The most frames kept. */
    public static final int MAX_FRAMES = 4;

    /** Bound on the stack frames inspected, so a deep stack never costs more. */
    static final int MAX_WALKED_FRAMES = 128;

    private static final StackWalker STACK_WALKER = StackWalker.getInstance();

    public ApplicationFrames {
        frames = List.copyOf(frames);
    }

    /** The frames of a stack, every one counted as retained alone. */
    public static ApplicationFrames of(List<String> frames) {
        int bytes = 0;
        for (String frame : frames) {
            bytes += RuntimeEvent.stringBytes(frame);
        }
        return new ApplicationFrames(frames, bytes);
    }

    /**
     * Whether a recorder walks the stack for an operation: only when its call-site setting is on, which is what turns
     * the walk off for both the panel and the runtime journal, and something keeps the frames, either the panel
     * capturing now or the journal recording the operation's source.
     */
    public static boolean wanted(boolean panelCaptures, boolean callSites, boolean journalRecords) {
        return callSites && (panelCaptures || journalRecords);
    }

    /**
     * The application frames of the calling thread's stack, or {@code null} when none is found within
     * {@value #MAX_WALKED_FRAMES} frames or the walk fails. Fully guarded, so it never disrupts the captured work.
     */
    public static ApplicationFrames capture() {
        try {
            return STACK_WALKER.walk(ApplicationFrames::select);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Selects the application frames of a stack: frames outside the JDK, frameworks, drivers, pools, and BootUI (see
     * {@link StackFramePrefixes}), and outside proxies generated around application classes. Exposed for tests with a synthetic stack.
     */
    public static ApplicationFrames select(Stream<StackWalker.StackFrame> stack) {
        List<String> frames = new ArrayList<>(MAX_FRAMES);
        stack.limit(MAX_WALKED_FRAMES)
                .filter(frame -> !StackFramePrefixes.isFrameworkClass(frame.getClassName())
                        && !isGenerated(frame.getClassName()))
                .limit(MAX_FRAMES)
                .forEach(frame -> frames.add(format(frame)));
        return frames.isEmpty() ? null : of(frames);
    }

    /**
     * Whether a class is a proxy generated around application code, such as Spring's CGLIB subclasses and Quarkus's
     * ArC subclasses and client proxies: its frame repeats the method it delegates to.
     */
    static boolean isGenerated(String className) {
        return className != null
                && (className.contains("$$") || className.endsWith("_Subclass") || className.endsWith("_ClientProxy"));
    }

    /** The innermost frame: the operation's call site. */
    public String callSite() {
        return frames.isEmpty() ? null : frames.get(0);
    }

    /** These frames, each replaced by the run's shared copy when the dictionary takes it. */
    public ApplicationFrames interned(JournalDictionary dictionary) {
        List<String> shared = new ArrayList<>(frames.size());
        int unshared = 0;
        for (String frame : frames) {
            String canonical = dictionary.canonical(frame);
            if (canonical == null) {
                shared.add(frame);
                unshared += RuntimeEvent.stringBytes(frame);
            } else {
                shared.add(canonical);
            }
        }
        return new ApplicationFrames(shared, unshared);
    }

    /** The bytes these frames retain beyond the dictionary: a reference each, and every unshared frame. */
    public int estimatedBytes() {
        return 16 + 8 * frames.size() + unsharedBytes;
    }

    private static String format(StackWalker.StackFrame frame) {
        String file = frame.getFileName();
        String position = file == null
                ? "Unknown Source"
                : (frame.getLineNumber() >= 0 ? file + ":" + frame.getLineNumber() : file);
        return frame.getClassName() + "." + frame.getMethodName() + "(" + position + ")";
    }
}
