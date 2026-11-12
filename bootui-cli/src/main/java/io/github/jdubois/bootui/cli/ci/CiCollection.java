package io.github.jdubois.bootui.cli.ci;

import io.github.jdubois.bootui.client.BootUiClientException;
import io.github.jdubois.bootui.client.JsonParseException;
import io.github.jdubois.bootui.client.JsonValue;
import io.github.jdubois.bootui.client.JsonWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Explicit test-lifecycle collection windows and an offline, fail-closed expected-window gate.
 *
 * <p>The caller owns the test workload and invokes {@link Window#finish()} before its application is stopped.
 * The expected assessment ID and plan digest must be retained separately from the writable evidence root.
 */
public final class CiCollection {

    private static final Set<String> ROOT_ENTRIES = Set.of("plan.json", "policy.json", "slots");
    private static final Set<String> SLOT_ENTRIES =
            Set.of("claim.json", "completion.json", "witness.json", "bundle.json", "summary.md", "junit.xml");
    private static final Set<String> CLAIM_KEYS =
            Set.of("schemaVersion", "planSha256", "assessmentId", "contextId", "attemptId");
    private static final Set<String> COMPLETION_KEYS = Set.of(
            "schemaVersion",
            "planSha256",
            "assessmentId",
            "contextId",
            "attemptId",
            "policySha256",
            "status",
            "errorCode",
            "witnessSha256",
            "bundleSha256",
            "summarySha256",
            "junitSha256");
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final int MAX_WITNESS_BYTES = 16 * 1024;
    private static final int MAX_JUNIT_BYTES = 256 * 1024;

    private CiCollection() {}

    /**
     * Creates a fresh, absolute evidence root and returns its plan SHA-256 for the caller to retain independently.
     * The policy bytes and ordered, nonempty expected context IDs are fixed for the whole root.
     */
    public static String prepare(Path requestedRoot, String assessmentId, Path policyFile, List<String> contextIds)
            throws IOException {
        Path root = CiEvidenceStore.absoluteRoot(requestedRoot);
        byte[] policyBytes = CiEvidenceStore.readRegularPath(policyFile, CiPolicy.MAX_POLICY_BYTES);
        CiPolicy policy = CiPolicy.readSnapshot(policyBytes);
        CiCollectionPlan plan =
                new CiCollectionPlan(assessmentId, CiEvidenceStore.nonce(), policy.sha256(), contextIds);
        byte[] planBytes = plan.bytes();
        Path parent;
        try {
            parent = root.getParent().toRealPath();
        } catch (NoSuchFileException missingParent) {
            throw new IOException("The evidence root's parent directory must already exist", missingParent);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            if (!(stream instanceof SecureDirectoryStream<?>)) {
                throw new IOException("The filesystem does not support secure evidence directory access");
            }
        }
        Path actualRoot = parent.resolve(root.getFileName());
        Files.createDirectory(actualRoot);
        Path slots = Files.createDirectory(actualRoot.resolve("slots"));
        for (String context : plan.contexts()) {
            Files.createDirectory(slots.resolve(plan.contextKey(context)));
        }
        try (SecureDirectoryStream<Path> directory = CiEvidenceStore.openRoot(actualRoot)) {
            CiEvidenceStore.writeNew(directory, "policy.json", policyBytes);
            CiEvidenceStore.writeNew(directory, "plan.json", planBytes);
        }
        return CiEvidenceStore.sha256(planBytes);
    }

    /**
     * Claims one planned window and attaches without running scans. Refusals do not interrupt the workload:
     * inspect {@link Window#attached()} and always call {@link Window#finish()} before application teardown.
     */
    public static Window begin(
            Path root,
            String expectedPlanSha256,
            String expectedAssessmentId,
            String contextId,
            String url,
            String apiPath,
            int timeoutSeconds)
            throws IOException {
        try (SecureDirectoryStream<Path> directory = CiEvidenceStore.openRoot(root)) {
            CiCollectionPlan.Snapshot snapshot;
            try {
                snapshot = CiCollectionPlan.load(directory, expectedPlanSha256, expectedAssessmentId);
            } catch (IOException | IllegalArgumentException | SecurityException invalid) {
                markRefused(directory);
                return Window.refused("INVALID_PLAN_OR_POLICY");
            }
            if (!CiCollectionPlan.validIdentifier(contextId)
                    || !snapshot.plan().contexts().contains(contextId)) {
                markRefused(directory);
                return Window.refused("UNEXPECTED_CONTEXT");
            }
            String attemptId = CiEvidenceStore.nonce();
            String assessmentId = snapshot.plan().windowAssessmentId(snapshot.planSha256(), contextId);
            try (SecureDirectoryStream<Path> slots = CiEvidenceStore.openDirectory(directory, "slots")) {
                SecureDirectoryStream<Path> slot =
                        CiEvidenceStore.openDirectory(slots, snapshot.plan().contextKey(contextId));
                try {
                    try {
                        CiEvidenceStore.writeNew(
                                slot, "claim.json", recordBytes(claim(snapshot, contextId, assessmentId, attemptId)));
                    } catch (FileAlreadyExistsException duplicate) {
                        slot.close();
                        markRefused(directory);
                        return Window.refused("DUPLICATE_CONTEXT");
                    }
                    Staging staging = new Staging();
                    try {
                        Files.write(
                                staging.root.resolve("policy.json"),
                                snapshot.policyBytes(),
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE);
                        CiAssessmentRunner runner = null;
                        String failure = null;
                        try {
                            runner = new CiAssessmentRunner(
                                    url,
                                    apiPath,
                                    timeoutSeconds,
                                    assessmentId,
                                    staging.root.resolve("policy.json"),
                                    staging.root.resolve("witness.json"),
                                    staging.root.resolve("reports"));
                            if (runner.attach() != 0) {
                                failure = runner.attachFailureCode();
                                if (failure == null) {
                                    failure = "ATTACH_FAILED";
                                }
                            }
                        } catch (IllegalArgumentException invalid) {
                            failure = "INVALID_TARGET_OR_OPTIONS";
                        }
                        if (failure != null) {
                            staging.close();
                            staging = null;
                        }
                        return new Window(snapshot, contextId, assessmentId, attemptId, slot, staging, runner, failure);
                    } catch (IOException | RuntimeException failure) {
                        try {
                            staging.close();
                        } catch (IOException cleanup) {
                            failure.addSuppressed(cleanup);
                        }
                        throw failure;
                    }
                } catch (IOException | RuntimeException failure) {
                    try {
                        slot.close();
                    } catch (IOException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                    throw failure;
                }
            }
        }
    }

    /**
     * Checks every expected window after all producer processes have finished. It never contacts an application.
     * Errors take precedence over threshold violations.
     */
    public static Result check(Path root, String expectedPlanSha256, String expectedAssessmentId) {
        List<ContextResult> contexts = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try (SecureDirectoryStream<Path> directory = CiEvidenceStore.openRoot(root)) {
            Set<String> rootEntries = CiEvidenceStore.names(directory, ROOT_ENTRIES.size() + 1);
            if (!rootEntries.containsAll(ROOT_ENTRIES)
                    || rootEntries.stream().anyMatch(name -> !ROOT_ENTRIES.contains(name) && !"refused".equals(name))) {
                errors.add("UNEXPECTED_EVIDENCE_ROOT");
            }
            if (rootEntries.contains("refused")) {
                errors.add("REFUSED_CONTEXT_CLAIM");
            }
            CiCollectionPlan.Snapshot snapshot =
                    CiCollectionPlan.load(directory, expectedPlanSha256, expectedAssessmentId);
            try (SecureDirectoryStream<Path> slots = CiEvidenceStore.openDirectory(directory, "slots")) {
                Set<String> actual = CiEvidenceStore.names(slots, CiCollectionPlan.MAX_CONTEXTS + 1);
                Set<String> expected = snapshot.plan().contexts().stream()
                        .map(snapshot.plan()::contextKey)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                if (!expected.containsAll(actual)) {
                    errors.add("UNEXPECTED_CONTEXT_EVIDENCE");
                }
                for (String context : snapshot.plan().contexts()) {
                    String key = snapshot.plan().contextKey(context);
                    if (!actual.contains(key)) {
                        contexts.add(new ContextResult(context, "NOT_ASSESSED", "MISSING_CONTEXT"));
                        continue;
                    }
                    try (SecureDirectoryStream<Path> slot = CiEvidenceStore.openDirectory(slots, key)) {
                        contexts.add(checkWindow(slot, snapshot, context));
                    } catch (IOException | IllegalArgumentException | JsonParseException | SecurityException invalid) {
                        contexts.add(new ContextResult(context, "NOT_ASSESSED", "INVALID_CONTEXT_EVIDENCE"));
                    }
                }
            }
        } catch (IOException | IllegalArgumentException | JsonParseException | SecurityException invalid) {
            errors.add("INVALID_PLAN_OR_EVIDENCE_ROOT");
        }
        boolean incomplete =
                !errors.isEmpty() || contexts.stream().anyMatch(context -> "NOT_ASSESSED".equals(context.status()));
        boolean violation = contexts.stream().anyMatch(context -> "VIOLATION".equals(context.status()));
        return new Result(
                incomplete ? "NOT_ASSESSED" : violation ? "VIOLATION" : "PASS",
                incomplete ? 1 : violation ? 3 : 0,
                contexts,
                errors);
    }

    /** Returns the stable directory containing one planned window's sanitized reports. */
    public static Path evidenceDirectory(Path root, String contextId) {
        if (!CiCollectionPlan.validIdentifier(contextId)) {
            throw new IllegalArgumentException("Invalid CI collection context");
        }
        return CiEvidenceStore.absoluteRoot(root)
                .resolve("slots")
                .resolve(CiEvidenceStore.sha256(contextId.getBytes(StandardCharsets.UTF_8)));
    }

    private static ContextResult checkWindow(
            SecureDirectoryStream<Path> slot, CiCollectionPlan.Snapshot snapshot, String contextId) throws IOException {
        Set<String> names = CiEvidenceStore.names(slot, SLOT_ENTRIES.size());
        if (!SLOT_ENTRIES.containsAll(names)) {
            throw new IllegalArgumentException("Unexpected context evidence");
        }
        if (!names.contains("claim.json")) {
            return new ContextResult(contextId, "NOT_ASSESSED", "MISSING_CLAIM");
        }
        if (!names.contains("completion.json")) {
            return new ContextResult(contextId, "NOT_ASSESSED", "INCOMPLETE_CONTEXT");
        }
        String assessmentId = snapshot.plan().windowAssessmentId(snapshot.planSha256(), contextId);
        JsonValue claim =
                CiCollectionPlan.parse(CiEvidenceStore.read(slot, "claim.json", CiCollectionPlan.MAX_RECORD_BYTES));
        CiCollectionPlan.requireKeys(claim, CLAIM_KEYS);
        String attemptId = claim.get("attemptId").asString(null);
        requireRecordIdentity(claim, snapshot, contextId, assessmentId, attemptId);

        JsonValue completion = CiCollectionPlan.parse(
                CiEvidenceStore.read(slot, "completion.json", CiCollectionPlan.MAX_RECORD_BYTES));
        CiCollectionPlan.requireKeys(completion, COMPLETION_KEYS);
        requireRecordIdentity(completion, snapshot, contextId, assessmentId, attemptId);
        if (!snapshot.policy().sha256().equals(completion.get("policySha256").asString(null))) {
            throw new IllegalArgumentException("The completion policy does not match");
        }
        String status = completion.get("status").asString(null);
        String errorCode = completion.get("errorCode").asString(null);
        if ("NOT_ASSESSED".equals(status)) {
            if (errorCode == null || !ERROR_CODE.matcher(errorCode).matches()) {
                throw new IllegalArgumentException("Invalid collection failure");
            }
            return new ContextResult(contextId, status, errorCode);
        }
        if (status == null
                || !Set.of("PASS", "VIOLATION").contains(status)
                || !"".equals(errorCode)
                || !names.equals(SLOT_ENTRIES)) {
            throw new IllegalArgumentException("Incomplete collection evidence");
        }
        byte[] witness = readMatching(slot, "witness.json", MAX_WITNESS_BYTES, completion, "witnessSha256");
        byte[] bundle =
                readMatching(slot, "bundle.json", CiAssessmentRunner.MAX_BUNDLE_BYTES, completion, "bundleSha256");
        readMatching(slot, "summary.md", CiAssessmentRunner.MAX_SUMMARY_BYTES, completion, "summarySha256");
        readMatching(slot, "junit.xml", MAX_JUNIT_BYTES, completion, "junitSha256");
        int exitCode = verifyEvidence(snapshot, assessmentId, witness, bundle);
        if ((exitCode == 0) != "PASS".equals(status)) {
            throw new IllegalArgumentException("The collection verdict does not match its evidence");
        }
        return new ContextResult(contextId, status, "");
    }

    private static byte[] readMatching(
            SecureDirectoryStream<Path> slot, String name, int maximum, JsonValue completion, String hashField)
            throws IOException {
        String expected = completion.get(hashField).asString(null);
        byte[] bytes = CiEvidenceStore.read(slot, name, maximum);
        if (!CiCollectionPlan.validDigest(expected) || !expected.equals(CiEvidenceStore.sha256(bytes))) {
            throw new IllegalArgumentException("An evidence digest does not match");
        }
        return bytes;
    }

    private static int verifyEvidence(
            CiCollectionPlan.Snapshot snapshot, String assessmentId, byte[] witnessBytes, byte[] bundleBytes) {
        JsonValue witness = CiAssessmentRunner.validateWitnessSnapshot(witnessBytes, assessmentId, snapshot.policy());
        CiBundleEvaluator.Result result =
                CiBundleEvaluator.evaluateSnapshot(bundleBytes, assessmentId, snapshot.policy());
        if (result.exitCode() != 0 && result.exitCode() != 3) {
            throw new IllegalArgumentException("The assessment bundle is invalid or incomplete");
        }
        JsonValue bundle = CiBundleEvaluator.parseBundle(bundleBytes);
        for (String field : List.of("bootUiVersion", "instanceId", "runId", "policySha256", "assessmentId")) {
            if (!witness.get(field).asString("").equals(bundle.get(field).asString(null))) {
                throw new IllegalArgumentException("The witness and assessment identity do not match");
            }
        }
        String witnessFramework = frameworkName(witness);
        if (!witnessFramework.equals(frameworkName(bundle))) {
            throw new IllegalArgumentException("The witness and assessment framework do not match");
        }
        return result.exitCode();
    }

    private static String frameworkName(JsonValue evidence) {
        return "1".equals(evidence.get("schemaVersion").asDisplayText())
                ? "Spring Boot"
                : evidence.get("frameworkName").asString("");
    }

    private static void requireRecordIdentity(
            JsonValue record,
            CiCollectionPlan.Snapshot snapshot,
            String contextId,
            String assessmentId,
            String attemptId) {
        if (!record.get("schemaVersion").isNumber()
                || !"1".equals(record.get("schemaVersion").asDisplayText())
                || !snapshot.planSha256().equals(record.get("planSha256").asString(null))
                || !contextId.equals(record.get("contextId").asString(null))
                || !assessmentId.equals(record.get("assessmentId").asString(null))
                || !CiCollectionPlan.validNonce(attemptId)
                || !attemptId.equals(record.get("attemptId").asString(null))) {
            throw new IllegalArgumentException("The collection record does not match its claim");
        }
    }

    private static Map<String, JsonValue> claim(
            CiCollectionPlan.Snapshot snapshot, String contextId, String assessmentId, String attemptId) {
        Map<String, JsonValue> record = new LinkedHashMap<>();
        record.put("schemaVersion", JsonValue.of(1));
        record.put("planSha256", JsonValue.of(snapshot.planSha256()));
        record.put("assessmentId", JsonValue.of(assessmentId));
        record.put("contextId", JsonValue.of(contextId));
        record.put("attemptId", JsonValue.of(attemptId));
        return record;
    }

    private static byte[] recordBytes(Map<String, JsonValue> record) {
        return (JsonWriter.pretty(JsonValue.object(record)) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void markRefused(SecureDirectoryStream<Path> root) throws IOException {
        try {
            CiEvidenceStore.writeNew(root, "refused", new byte[] {1});
        } catch (FileAlreadyExistsException alreadyRefused) {
            // Presence alone makes the offline gate fail, regardless of the existing entry's type.
        }
    }

    public record ContextResult(String contextId, String status, String errorCode) {}

    public record Result(String status, int exitCode, List<ContextResult> contexts, List<String> errors) {
        public Result {
            contexts = List.copyOf(contexts);
            errors = List.copyOf(errors);
        }
    }

    /** A single claimed collection window; a refused attach never runs scans. */
    public static final class Window {

        private final CiCollectionPlan.Snapshot snapshot;
        private final String contextId;
        private final String assessmentId;
        private final String attemptId;
        private final SecureDirectoryStream<Path> slot;
        private final Staging staging;
        private final CiAssessmentRunner runner;
        private final String attachFailure;
        private boolean finished;
        private int exitCode = 1;

        private Window(
                CiCollectionPlan.Snapshot snapshot,
                String contextId,
                String assessmentId,
                String attemptId,
                SecureDirectoryStream<Path> slot,
                Staging staging,
                CiAssessmentRunner runner,
                String attachFailure) {
            this.snapshot = snapshot;
            this.contextId = contextId;
            this.assessmentId = assessmentId;
            this.attemptId = attemptId;
            this.slot = slot;
            this.staging = staging;
            this.runner = runner;
            this.attachFailure = attachFailure;
        }

        private static Window refused(String reason) {
            return new Window(null, null, null, null, null, null, null, reason);
        }

        public boolean attached() {
            return attachFailure == null;
        }

        public String attachFailureCode() {
            return attachFailure;
        }

        /** Completes the owned window once. Ordinary assessment failures are recorded and returned as exit 1. */
        public synchronized int finish() throws IOException {
            if (finished) {
                return exitCode;
            }
            finished = true;
            if (slot == null) {
                return exitCode;
            }
            try (slot;
                    staging) {
                Evidence evidence = null;
                String error = attachFailure;
                int assessmentExit = 1;
                if (error == null) {
                    try {
                        assessmentExit = runner.assess();
                        if (assessmentExit == 0 || assessmentExit == 3) {
                            evidence = Evidence.read(staging.root);
                            if (verifyEvidence(snapshot, assessmentId, evidence.witness(), evidence.bundle())
                                    != assessmentExit) {
                                throw new IllegalArgumentException("The assessment result does not match its bundle");
                            }
                        } else {
                            error = "ASSESSMENT_NOT_COMPLETED";
                        }
                    } catch (IOException
                            | BootUiClientException
                            | IllegalArgumentException
                            | JsonParseException invalid) {
                        error = "EVIDENCE_COLLECTION_FAILED";
                    }
                }
                if (staging != null) {
                    staging.close();
                }
                if (error == null && evidence != null) {
                    CiEvidenceStore.writeNew(slot, "witness.json", evidence.witness());
                    CiEvidenceStore.writeNew(slot, "bundle.json", evidence.bundle());
                    CiEvidenceStore.writeNew(slot, "summary.md", evidence.summary());
                    CiEvidenceStore.writeNew(slot, "junit.xml", evidence.junit());
                }
                int resultExitCode = error == null ? assessmentExit : 1;
                Map<String, JsonValue> completion = claim(snapshot, contextId, assessmentId, attemptId);
                completion.put("policySha256", JsonValue.of(snapshot.policy().sha256()));
                completion.put(
                        "status",
                        JsonValue.of(error != null ? "NOT_ASSESSED" : resultExitCode == 0 ? "PASS" : "VIOLATION"));
                completion.put("errorCode", JsonValue.of(error == null ? "" : error));
                completion.put(
                        "witnessSha256",
                        JsonValue.of(evidence == null ? "" : CiEvidenceStore.sha256(evidence.witness())));
                completion.put(
                        "bundleSha256",
                        JsonValue.of(evidence == null ? "" : CiEvidenceStore.sha256(evidence.bundle())));
                completion.put(
                        "summarySha256",
                        JsonValue.of(evidence == null ? "" : CiEvidenceStore.sha256(evidence.summary())));
                completion.put(
                        "junitSha256", JsonValue.of(evidence == null ? "" : CiEvidenceStore.sha256(evidence.junit())));
                CiEvidenceStore.writeNew(slot, "completion.json", recordBytes(completion));
                exitCode = resultExitCode;
                return exitCode;
            }
        }
    }

    private record Evidence(byte[] witness, byte[] bundle, byte[] summary, byte[] junit) {
        static Evidence read(Path staging) throws IOException {
            Path reports = staging.resolve("reports");
            return new Evidence(
                    CiEvidenceStore.readRegularPath(staging.resolve("witness.json"), MAX_WITNESS_BYTES),
                    CiEvidenceStore.readRegularPath(
                            reports.resolve("bundle.json"), CiAssessmentRunner.MAX_BUNDLE_BYTES),
                    CiEvidenceStore.readRegularPath(
                            reports.resolve("summary.md"), CiAssessmentRunner.MAX_SUMMARY_BYTES),
                    CiEvidenceStore.readRegularPath(reports.resolve("junit.xml"), MAX_JUNIT_BYTES));
        }
    }

    private static final class Staging implements AutoCloseable {

        private final Path root = Files.createTempDirectory("bootui-ci-context-");
        private boolean closed;

        private Staging() throws IOException {}

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            BasicFileAttributes rootAttributes =
                    Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!rootAttributes.isDirectory()) {
                throw new IOException("The temporary CI evidence directory changed");
            }
            Path reports = root.resolve("reports");
            if (Files.exists(reports, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes =
                        Files.readAttributes(reports, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory()) {
                    throw new IOException("The temporary CI report directory changed");
                }
                for (String name : CiAssessmentRunner.REPORT_FILES) {
                    Files.deleteIfExists(reports.resolve(name));
                }
                Files.delete(reports);
            }
            Files.deleteIfExists(root.resolve("witness.json"));
            Files.deleteIfExists(root.resolve("policy.json"));
            Files.delete(root);
            closed = true;
        }
    }
}
