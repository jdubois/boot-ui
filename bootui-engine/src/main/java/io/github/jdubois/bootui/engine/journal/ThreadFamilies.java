package io.github.jdubois.bootui.engine.journal;

/**
 * Groups thread names into families by replacing every run of digits with {@code N}, so {@code pool-3-thread-7} and
 * {@code pool-3-thread-12} are one family, {@code pool-N-thread-N} ({@code docs/PLAN-v2.md} §5.2).
 */
public final class ThreadFamilies {

    /** The family of a thread with no name. */
    public static final String UNKNOWN = "(unknown thread)";

    private ThreadFamilies() {}

    public static String of(String threadName) {
        if (threadName == null || threadName.isBlank()) {
            return UNKNOWN;
        }
        StringBuilder family = new StringBuilder(threadName.length());
        boolean inDigits = false;
        for (int i = 0; i < threadName.length(); i++) {
            char c = threadName.charAt(i);
            if (c >= '0' && c <= '9') {
                if (!inDigits) {
                    family.append('N');
                    inDigits = true;
                }
            } else {
                family.append(c);
                inDigits = false;
            }
        }
        return family.toString();
    }
}
