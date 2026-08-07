package io.github.jdubois.bootui.engine.crac;

import java.util.List;

/**
 * Live, in-process view of the host application's Spring-managed CRaC signals, captured once per scan
 * so readiness checks can reason about auto-configured resources that never appear in the
 * application's own bytecode.
 *
 * <p>Connection pools are the most common real-world cause of a failed CRaC checkpoint: if a pooled
 * socket is still open when the checkpoint is taken, CRaC aborts with a
 * {@code CheckpointOpenSocketException}. Because the pools are contributed by framework
 * auto-configuration rather than the application package that the ArchUnit importer scans, the
 * connection-pool check reads this runtime inventory instead of imported classes.</p>
 *
 * @param connectionPoolBeans human-readable {@code beanName : TypeName} entries for detected
 *     non-Hikari pool beans and remote clients that need library-specific lifecycle review, empty when
 *     none are present
 * @param cacheManagerBeans human-readable {@code beanName : TypeName} entries for known local, in-heap
 *     cache manager implementations, empty when none are present
 * @param hikariPoolIssues bounded observations for Hikari pools whose Spring Boot checkpoint lifecycle
 *     or pool-suspension state is absent or cannot be verified, empty when every detected Hikari pool
 *     has positive lifecycle evidence
 * @param unmanagedTaskBeans human-readable {@code beanName : TypeName} entries for Spring
 *     thread-per-task executors or schedulers that do not fully participate in context lifecycle
 *     management, empty when none are present
 * @param cracApiPresent whether the application-facing {@code org.crac:crac} compatibility API is
 *     present on the application's classpath; defaults to {@code true} in every convenience
 *     constructor and in {@link #empty()} so that a collection failure or an unavailable runtime never
 *     spuriously reports the dependency as missing
 * @param checkpointOnRefresh whether Spring Framework will take an automatic checkpoint immediately
 *     after context refresh; used to suppress checks that apply only to on-demand checkpoints
 * @param restoredProcess whether the current JVM was started from {@code -XX:CRaCRestoreFrom}; a
 *     restored process has already consumed the original checkpoint-on-refresh phase
 */
public record CracRuntimeInventory(
        List<String> connectionPoolBeans,
        List<String> cacheManagerBeans,
        List<String> hikariPoolIssues,
        List<String> unmanagedTaskBeans,
        boolean cracApiPresent,
        boolean checkpointOnRefresh,
        boolean restoredProcess) {

    public CracRuntimeInventory {
        connectionPoolBeans = connectionPoolBeans == null ? List.of() : List.copyOf(connectionPoolBeans);
        cacheManagerBeans = cacheManagerBeans == null ? List.of() : List.copyOf(cacheManagerBeans);
        hikariPoolIssues = hikariPoolIssues == null ? List.of() : List.copyOf(hikariPoolIssues);
        unmanagedTaskBeans = unmanagedTaskBeans == null ? List.of() : List.copyOf(unmanagedTaskBeans);
    }

    public CracRuntimeInventory(List<String> connectionPoolBeans, List<String> cacheManagerBeans) {
        this(connectionPoolBeans, cacheManagerBeans, List.of(), List.of(), true, false, false);
    }

    public CracRuntimeInventory(
            List<String> connectionPoolBeans, List<String> cacheManagerBeans, boolean cracApiPresent) {
        this(connectionPoolBeans, cacheManagerBeans, List.of(), List.of(), cracApiPresent, false, false);
    }

    public CracRuntimeInventory(
            List<String> connectionPoolBeans,
            List<String> cacheManagerBeans,
            boolean cracApiPresent,
            boolean checkpointOnRefresh) {
        this(connectionPoolBeans, cacheManagerBeans, List.of(), List.of(), cracApiPresent, checkpointOnRefresh, false);
    }

    public CracRuntimeInventory(List<String> connectionPoolBeans) {
        this(connectionPoolBeans, List.of(), List.of(), List.of(), true, false, false);
    }

    public static CracRuntimeInventory empty() {
        return new CracRuntimeInventory(List.of(), List.of(), List.of(), List.of(), true, false, false);
    }
}
