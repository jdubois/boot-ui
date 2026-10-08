package bootuiagentit;

/**
 * A framework-like singleton holding a thread local in an instance field (PLAN-v2 §5.16, M5-5f), as SLF4J's MDC adapter
 * does: the resolver reaches it one level deep from the static field holding the singleton.
 */
public final class FrameworkLike {

    static final FrameworkLike INSTANCE = new FrameworkLike();

    final ThreadLocal<String> local = new ThreadLocal<>();

    private FrameworkLike() {}
}
