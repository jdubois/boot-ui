package io.github.jdubois.bootui.cli.ci;

import io.github.jdubois.bootui.client.JsonParseException;
import io.github.jdubois.bootui.client.JsonValue;
import io.github.jdubois.bootui.client.JsonWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.SecureDirectoryStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The independently pinned, single-policy set of expected collection windows. */
record CiCollectionPlan(String assessmentId, String nonce, String policySha256, List<String> contexts) {

    static final int MAX_CONTEXTS = 128;
    static final int MAX_PLAN_BYTES = 64 * 1024;
    static final int MAX_RECORD_BYTES = 16 * 1024;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern NONCE = Pattern.compile("[0-9a-f]{32}");
    private static final Set<String> PLAN_KEYS =
            Set.of("schemaVersion", "assessmentId", "nonce", "policySha256", "contexts");

    CiCollectionPlan {
        if (!validIdentifier(assessmentId)
                || nonce == null
                || !NONCE.matcher(nonce).matches()
                || !validDigest(policySha256)
                || contexts == null
                || contexts.isEmpty()
                || contexts.size() > MAX_CONTEXTS
                || contexts.stream().anyMatch(context -> !validIdentifier(context))
                || new HashSet<>(contexts).size() != contexts.size()) {
            throw new IllegalArgumentException("Invalid CI collection plan");
        }
        contexts = List.copyOf(contexts);
    }

    byte[] bytes() {
        Map<String, JsonValue> plan = new LinkedHashMap<>();
        plan.put("schemaVersion", JsonValue.of(1));
        plan.put("assessmentId", JsonValue.of(assessmentId));
        plan.put("nonce", JsonValue.of(nonce));
        plan.put("policySha256", JsonValue.of(policySha256));
        plan.put(
                "contexts", JsonValue.array(contexts.stream().map(JsonValue::of).toList()));
        byte[] bytes = (JsonWriter.pretty(JsonValue.object(plan)) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_PLAN_BYTES) {
            throw new IllegalArgumentException("The CI collection plan exceeds its byte limit");
        }
        return bytes;
    }

    static Snapshot load(SecureDirectoryStream<java.nio.file.Path> root, String expectedDigest, String expectedId)
            throws IOException {
        if (!validDigest(expectedDigest) || !validIdentifier(expectedId)) {
            throw new IllegalArgumentException("An expected plan digest and assessment ID are required");
        }
        byte[] bytes = CiEvidenceStore.read(root, "plan.json", MAX_PLAN_BYTES);
        String actualDigest = CiEvidenceStore.sha256(bytes);
        if (!actualDigest.equals(expectedDigest)) {
            throw new IllegalArgumentException("The prepared CI plan does not match the pinned digest");
        }
        JsonValue json = parse(bytes);
        requireKeys(json, PLAN_KEYS);
        if (!"1".equals(json.get("schemaVersion").asDisplayText())
                || !json.get("schemaVersion").isNumber()
                || !json.get("contexts").isArray()) {
            throw new IllegalArgumentException("Invalid CI collection plan");
        }
        List<String> contexts = new ArrayList<>();
        for (JsonValue context : json.get("contexts").values()) {
            if (!context.isString() || contexts.size() >= MAX_CONTEXTS) {
                throw new IllegalArgumentException("Invalid CI collection context");
            }
            contexts.add(context.asString(null));
        }
        CiCollectionPlan plan = new CiCollectionPlan(
                json.get("assessmentId").asString(null),
                json.get("nonce").asString(null),
                json.get("policySha256").asString(null),
                contexts);
        if (!expectedId.equals(plan.assessmentId())) {
            throw new IllegalArgumentException("The prepared CI assessment ID does not match");
        }
        byte[] policyBytes = CiEvidenceStore.read(root, "policy.json", CiPolicy.MAX_POLICY_BYTES);
        CiPolicy policy = CiPolicy.readSnapshot(policyBytes);
        if (!plan.policySha256().equals(policy.sha256())) {
            throw new IllegalArgumentException("The prepared CI policy has changed");
        }
        return new Snapshot(plan, actualDigest, policy, policyBytes);
    }

    String contextKey(String contextId) {
        return CiEvidenceStore.sha256(contextId.getBytes(StandardCharsets.UTF_8));
    }

    String windowAssessmentId(String planSha256, String contextId) {
        String source = "bootui-ci-window-v1\n" + planSha256 + "\n" + assessmentId.length() + ":" + assessmentId + "\n"
                + contextId.length() + ":" + contextId;
        return "ctx-" + CiEvidenceStore.sha256(source.getBytes(StandardCharsets.UTF_8));
    }

    static JsonValue parse(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return JsonValue.parseStrict(text);
        } catch (CharacterCodingException | JsonParseException invalid) {
            throw new IllegalArgumentException("Invalid CI evidence JSON", invalid);
        }
    }

    static void requireKeys(JsonValue json, Set<String> keys) {
        if (!json.isObject() || !Set.copyOf(json.names()).equals(keys)) {
            throw new IllegalArgumentException("Invalid CI evidence fields");
        }
    }

    static boolean validIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    static boolean validDigest(String value) {
        return value != null && DIGEST.matcher(value).matches();
    }

    static boolean validNonce(String value) {
        return value != null && NONCE.matcher(value).matches();
    }

    record Snapshot(CiCollectionPlan plan, String planSha256, CiPolicy policy, byte[] policyBytes) {}
}
