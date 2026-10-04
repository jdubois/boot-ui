package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.support.StackFramePrefixes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Up to {@value #MAX_FRAMES} application frames above a captured operation, innermost first, such as the repository
 * method that ran a statement and the service and controller methods that called it ({@code docs/PLAN-v2.md} §5.2).
 * The first frame is the operation's call site. Each frame reads {@code ClassName.method(File.java:42)}, as call sites
 * and exception locations do.
 *
 * <p>The application thread only walks its stack and selects the frames ({@link #capture()}); it keeps the selected
 * stack frames as they are and formats them later, on first read, which in practice is the journal's dispatcher thread
 * interning the event (M4-18d). Formatting is idempotent, so concurrent readers see equal frames, and it drops the
 * stack frames, so a formatted instance pins no class. A frame whose class is redefined, by the BootUI agent's
 * retransformation or an IDE's hot swap, between its capture and its formatting reads {@code (Unknown Source)}, since
 * the JVM gives no line for a replaced method version; an event waits for the dispatcher only as long as the queue
 * takes to drain.</p>
 *
 * <p>The journal interns the frames in its run's dictionary when it retains the event, so a frame repeated by thousands
 * of events is stored once. {@link #estimatedBytes()} counts the frames the dictionary could not take. An interned
 * instance holds only its formatted frames, never the stack frames it was captured from.</p>
 */
public final class ApplicationFrames {

    /** The most frames kept. */
    public static final int MAX_FRAMES = 4;

    /** Bound on the stack frames inspected, so a deep stack never costs more. */
    static final int MAX_WALKED_FRAMES = 128;

    private static final StackWalker STACK_WALKER = StackWalker.getInstance();

    /**
     * The selected stack frames, innermost first, until formatted, then the {@link Formatted} frames, which replace them
     * in one volatile write, so a reader sees one or the other.
     */
    private volatile Object state;

    /** The innermost frame formatted alone, for a call site read before the other frames are formatted. */
    private volatile String innermost;

    private ApplicationFrames(List<String> frames, int unsharedBytes) {
        this.state = new Formatted(List.copyOf(frames), unsharedBytes);
    }

    private ApplicationFrames(StackWalker.StackFrame[] pending) {
        this.state = pending;
    }

    /** Formatted frames and the bytes of those this instance retains alone: all of them until interned. */
    private record Formatted(List<String> frames, int unsharedBytes) {}

    /** The frames of a stack, every one counted as retained alone. */
    public static ApplicationFrames of(List<String> frames) {
        return new ApplicationFrames(frames, bytesOf(frames));
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
     * {@value #MAX_WALKED_FRAMES} frames or the walk fails. Fully guarded, so it never disrupts the captured work. The
     * frames are selected now and formatted on first read.
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
     * {@link StackFramePrefixes}), and outside proxies generated around application classes. Exposed for tests with a
     * synthetic stack.
     */
    public static ApplicationFrames select(Stream<StackWalker.StackFrame> stack) {
        StackWalker.StackFrame[] selected = stack.limit(MAX_WALKED_FRAMES)
                .filter(frame -> !StackFramePrefixes.isFrameworkClass(frame.getClassName())
                        && !isGenerated(frame.getClassName()))
                .limit(MAX_FRAMES)
                .toArray(StackWalker.StackFrame[]::new);
        return selected.length == 0 ? null : new ApplicationFrames(selected);
    }

    /**
     * Whether a class is a proxy generated around application code, such as Spring's CGLIB subclasses and Quarkus's
     * ArC subclasses and client proxies: its frame repeats the method it delegates to.
     */
    static boolean isGenerated(String className) {
        return className != null
                && (className.contains("$$") || className.endsWith("_Subclass") || className.endsWith("_ClientProxy"));
    }

    /** Whether the frames are formatted yet; for tests that the application thread leaves them to the dispatcher. */
    boolean isFormatted() {
        return state instanceof Formatted;
    }

    /** The frames, innermost first. */
    public List<String> frames() {
        return formatted().frames();
    }

    /** The bytes of the frames this instance retains alone: all of them until interned. */
    public int unsharedBytes() {
        return formatted().unsharedBytes();
    }

    /**
     * The innermost frame: the operation's call site. Before the frames are formatted, only this one is, so a panel
     * reading its call site on the application thread leaves the others to the journal's dispatcher.
     */
    public String callSite() {
        Object current = state;
        if (current instanceof StackWalker.StackFrame[] pending) {
            String site = innermost;
            if (site == null) {
                try {
                    site = format(pending[0]);
                } catch (RuntimeException ex) {
                    return innermostOf(formatted());
                }
                innermost = site;
            }
            return site;
        }
        return innermostOf((Formatted) current);
    }

    /**
     * {@code frames} interned in {@code dictionary}, or {@code null} when there are none or none could be formatted, as
     * a payload retains them.
     */
    static ApplicationFrames interned(ApplicationFrames frames, JournalDictionary dictionary) {
        if (frames == null || frames.frames().isEmpty()) {
            return null;
        }
        return frames.interned(dictionary);
    }

    /** These frames, each replaced by the run's shared copy when the dictionary takes it. */
    public ApplicationFrames interned(JournalDictionary dictionary) {
        List<String> frames = frames();
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
        Formatted done = formatted();
        return 16 + 8 * done.frames().size() + done.unsharedBytes();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ApplicationFrames that && formatted().equals(that.formatted());
    }

    @Override
    public int hashCode() {
        return Objects.hash(formatted());
    }

    @Override
    public String toString() {
        Formatted done = formatted();
        return "ApplicationFrames[frames=" + done.frames() + ", unsharedBytes=" + done.unsharedBytes() + "]";
    }

    /**
     * The formatted frames, formatting the pending stack frames on the first call. Concurrent first calls each format
     * the same frames, so whichever result is published is equal. A frame that cannot be formatted, which the stack
     * walker's frames never cause in practice, ends the frames before it, so the innermost frames formatted remain.
     */
    private Formatted formatted() {
        Object current = state;
        if (current instanceof Formatted done) {
            return done;
        }
        StackWalker.StackFrame[] pending = (StackWalker.StackFrame[]) current;
        List<String> frames = new ArrayList<>(pending.length);
        try {
            String site = innermost;
            for (int i = 0; i < pending.length; i++) {
                frames.add(i == 0 && site != null ? site : format(pending[i]));
            }
        } catch (RuntimeException ex) {
            // Keeps the frames formatted before the one that failed, so the call site stays the innermost frame.
        }
        Formatted done = new Formatted(List.copyOf(frames), bytesOf(frames));
        state = done;
        return done;
    }

    private static String innermostOf(Formatted done) {
        return done.frames().isEmpty() ? null : done.frames().get(0);
    }

    private static int bytesOf(List<String> frames) {
        int bytes = 0;
        for (String frame : frames) {
            bytes += RuntimeEvent.stringBytes(frame);
        }
        return bytes;
    }

    private static String format(StackWalker.StackFrame frame) {
        String file = frame.getFileName();
        String position = file == null
                ? "Unknown Source"
                : (frame.getLineNumber() >= 0 ? file + ":" + frame.getLineNumber() : file);
        return frame.getClassName() + "." + frame.getMethodName() + "(" + position + ")";
    }
}
