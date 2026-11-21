package io.github.jdubois.bootui.engine.journal;

/** The complete framework-neutral identity of a durable journal producer. */
public record DurableJournalIdentity(
        String namespaceId, String applicationId, String runId, String processId, String instanceId) {

    public DurableJournalIdentity {
        DurableJournalScope scope = new DurableJournalScope(namespaceId, applicationId, runId);
        namespaceId = scope.namespace();
        applicationId = scope.application();
        runId = scope.run();
        processId = DurableJournalScope.bounded("processId", processId, 128);
        instanceId = DurableJournalScope.bounded("instanceId", instanceId, 128);
    }

    /**
     * Creates one durable identity, using a framework application key only when no application override is set.
     */
    public static DurableJournalIdentity create(
            String namespaceId, String legacyInstanceId, String applicationId, String runApplicationKey) {
        return new DurableJournalIdentity(
                DurableJournalIds.resolveNamespaceId(namespaceId, legacyInstanceId),
                DurableJournalIds.resolveApplicationId(applicationId, runApplicationKey),
                DurableJournalIds.newRunId(),
                DurableJournalIds.processId(),
                DurableJournalIds.instanceId());
    }

    /** Testable identity creation that does not inspect the host OS. */
    public static DurableJournalIdentity create(
            String namespaceId,
            String legacyInstanceId,
            String applicationId,
            String runApplicationKey,
            String userName,
            String hostName) {
        return new DurableJournalIdentity(
                DurableJournalIds.resolveNamespaceId(namespaceId, legacyInstanceId, userName, hostName),
                DurableJournalIds.resolveApplicationId(applicationId, runApplicationKey),
                DurableJournalIds.newRunId(),
                DurableJournalIds.processId(),
                DurableJournalIds.instanceId());
    }

    /** The storage scope selected by this identity. */
    public DurableJournalScope scope() {
        return new DurableJournalScope(namespaceId, applicationId, runId);
    }

    /** The same owner and process identity bound to the application's actual runtime run id. */
    public DurableJournalIdentity withRunId(String actualRunId) {
        return new DurableJournalIdentity(namespaceId, applicationId, actualRunId, processId, instanceId);
    }
}
