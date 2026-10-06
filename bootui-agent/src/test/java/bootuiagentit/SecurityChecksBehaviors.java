package bootuiagentit;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.SecuritySinks;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.crypto.Cipher;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

/**
 * The security-sinks sensor's JDK checks (PLAN-v2 §5.16, M5-6b2) in a forked JVM beside the agent: weak algorithms,
 * deserialization without a filter, and trust managers and hostname verifiers, with their counterexamples. Every JDK
 * class it hooks is loaded and used before the claim, so its hooks retransform them. Prints one PASS or FAIL line per
 * behavior, then the bridge's status. Modes: {@code check} claims the sensor alone and reports its self-test; {@code
 * beside} claims it with files, processes, network, and blocking; {@code behaviors} runs every behavior; {@code
 * mockito-first} and {@code bootui-first} run Mockito's {@code mockStatic(MessageDigest.class)} before or after the
 * claim.
 */
public final class SecurityChecksBehaviors {

    static final String REQUEST = "00000000000000e1";
    static final long REQUEST_BITS = 0xe1L;

    static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();
    static final List<String> RESULTS = new ArrayList<>();
    static final List<long[]> RECORDS = new ArrayList<>();
    static long token;
    static long generation;

    private SecurityChecksBehaviors() {}

    /** A class of the application, read back from a stream. */
    record Cart(String owner, List<Integer> items) implements Serializable {}

    /** A trust manager of the application. */
    static final class TrustEverything implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String type) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String type) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "behaviors";
        // Loaded and used before the claim, as an application's are: the hooks retransform them. No ObjectInputStream
        // is built before the filter factory behavior, which needs none built in this JVM.
        MessageDigest.getInstance("SHA-256");
        Cipher.getInstance("AES/GCM/NoPadding");
        SSLContext.getInstance("TLS");
        HttpsURLConnection.getDefaultHostnameVerifier();
        ObjectInputStream.class.getName();
        if (mode.equals("mockito-first")) {
            SecurityChecksMockito.mockStatic(mode);
        }
        List<String> sensors = mode.equals("beside")
                ? List.of(
                        SideEffects.FILES,
                        SideEffects.PROCESSES,
                        SideEffects.NETWORK,
                        SideEffects.BLOCKING,
                        SideEffects.SECURITY_SINKS)
                : List.of(SideEffects.SECURITY_SINKS);
        token = claim(sensors);
        for (String sensor : sensors) {
            FilesEnvironmentBehaviors.awaitSelfTest(sensor);
        }
        switch (mode) {
            case "behaviors", "beside" -> {
                filterFactoryStaysSettable();
                weakAlgorithms();
                cipherWithAProviderNameIsSeenOnce();
                strongAlgorithmsAreNotRecorded();
                libraryRequestIsApart();
                jdkOwnUseIsNotRecorded();
                unfilteredDeserialization();
                filteredDeserialization();
                trustManager();
                defaultHostnameVerifier();
                if (mode.equals("behaviors")) {
                    releaseRestores();
                }
            }
            case "limited" -> {
                // Run with --limit-modules leaving jdk.unsupported out: the deserialization group cannot self-test.
                weakAlgorithms();
                trustManager();
                deserializationOff();
                System.out.println("REQUEST_VALUES=" + io.github.jdubois.bootui.agent.bridge.RequestValues.active());
            }
            case "jvm-filter" -> jvmWideFilter();
            case "mockito-first", "bootui-first" -> {
                if (mode.equals("bootui-first")) {
                    SecurityChecksMockito.mockStatic(mode);
                }
                weakAlgorithms();
            }
            default -> {}
        }
        Map<String, Object> status = AgentBridge.status();
        System.out.println("SECURITY_SINKS=" + status.get(SideEffects.SECURITY_SINKS));
        System.out.println("SENSOR=" + SideEffectsBehaviors.sensor(SideEffects.SECURITY_SINKS));
        RESULTS.forEach(System.out::println);
        System.out.println("STATUS=" + status);
    }

    static long claim(List<String> sensors) {
        Supplier<Object> capture = () -> CONTEXT.get() == null
                ? null
                : new Object[] {CONTEXT.get(), null, null, null, "/checks", null, null, 1L, 1L};
        Function<Object, AutoCloseable> reopen = argument -> null;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "security-checks-behaviors");
        request.put("mode", "dev");
        request.put("packages", List.of("bootuiagentit"));
        request.put("sensors", sensors);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        System.out.println("CLAIM=" + result.get("status"));
        generation = (Long) result.get("generation");
        FilesEnvironmentBehaviors.generation = generation;
        return (Long) result.get("token");
    }

    static void filterFactoryStaysSettable() {
        String outcome;
        try {
            ObjectInputFilter.Config.setSerialFilterFactory((current, requested) -> requested);
            outcome = "set";
        } catch (IllegalStateException ex) {
            outcome = ex.getMessage();
        }
        check(
                "the application's serial filter factory can still be set after the self-test (" + outcome + ")",
                "set".equals(outcome));
    }

    static void weakAlgorithms() throws Exception {
        drain();
        RECORDS.clear();
        CONTEXT.set(REQUEST);
        MessageDigest.getInstance("MD5").digest("x".getBytes(StandardCharsets.UTF_8));
        Cipher.getInstance("AES/ECB/PKCS5Padding");
        Cipher.getInstance("DESede");
        CONTEXT.remove();
        long[] md5 = await(seen(SecuritySinks.KIND_WEAK_DIGEST, "MD5"));
        long[] ecb = await(seen(SecuritySinks.KIND_WEAK_CIPHER, "AES/ECB/PKCS5Padding"));
        long[] desede = await(seen(SecuritySinks.KIND_WEAK_CIPHER, "DESede"));
        check(
                "MD5, AES/ECB, and DESede asked for by the application are recorded with its call site and request ("
                        + describe() + ")",
                md5 != null
                        && ecb != null
                        && desede != null
                        && md5[SideEffects.R_REQUEST] == REQUEST_BITS
                        && origin(md5) == SecuritySinks.ORIGIN_APPLICATION
                        && outside(md5).equals("bootuiagentit.SecurityChecksBehaviors#weakAlgorithms")
                        && application(md5).equals("bootuiagentit.SecurityChecksBehaviors#weakAlgorithms"));
    }

    static void cipherWithAProviderNameIsSeenOnce() throws Exception {
        drain();
        RECORDS.clear();
        Cipher.getInstance("Blowfish/ECB/NoPadding", "SunJCE");
        long[] found = await(seen(SecuritySinks.KIND_WEAK_CIPHER, "Blowfish/ECB/NoPadding"));
        Thread.sleep(50);
        drain();
        long count = RECORDS.stream()
                .filter(matches(SecuritySinks.KIND_WEAK_CIPHER, "Blowfish/ECB/NoPadding"))
                .mapToLong(record -> record[SideEffects.R_COUNT])
                .sum();
        check(
                "Cipher.getInstance(String, String), which calls getInstance(String, Provider), is recorded once ("
                        + count + ")",
                found != null && count == 1);
    }

    static void strongAlgorithmsAreNotRecorded() throws Exception {
        drain();
        RECORDS.clear();
        MessageDigest.getInstance("SHA-256");
        MessageDigest.getInstance("SHA-512");
        Cipher.getInstance("AES/GCM/NoPadding");
        Cipher.getInstance("RSA/ECB/PKCS1Padding");
        Thread.sleep(50);
        drain();
        check(
                "SHA-256, SHA-512, AES/GCM/NoPadding, and RSA/ECB/PKCS1Padding are not recorded (" + describe() + ")",
                checks().isEmpty());
    }

    static void libraryRequestIsApart() throws Exception {
        drain();
        RECORDS.clear();
        bootuiagentitlibrary.Hashing.sha1(new byte[] {1});
        long[] sha1 = await(seen(SecuritySinks.KIND_WEAK_DIGEST, "SHA-1"));
        check(
                "a library's SHA-1 is recorded apart, with the application frame above it (" + describe() + ")",
                sha1 != null
                        && origin(sha1) == SecuritySinks.ORIGIN_LIBRARY
                        && outside(sha1).equals("bootuiagentitlibrary.Hashing#sha1")
                        && application(sha1).equals("bootuiagentit.SecurityChecksBehaviors#libraryRequestIsApart"));
    }

    static void jdkOwnUseIsNotRecorded() throws Exception {
        drain();
        RECORDS.clear();
        // UUID.nameUUIDFromBytes asks for MD5 itself: the JDK's own use, not the application's choice.
        UUID.nameUUIDFromBytes("bootui".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(50);
        drain();
        Object jdk = AgentBridge.status().get(SideEffects.SECURITY_SINKS) instanceof Map<?, ?> map
                ? map.get("jdkRequests")
                : null;
        check(
                "the JDK's own MD5 (UUID.nameUUIDFromBytes) is not recorded, only counted (" + jdk + ", " + describe()
                        + ")",
                checks().isEmpty() && jdk instanceof Long count && count > 0);
    }

    static void unfilteredDeserialization() throws Exception {
        drain();
        RECORDS.clear();
        byte[] bytes = serialize(new Cart("ada", new ArrayList<>(List.of(1, 2))));
        CONTEXT.set(REQUEST);
        Object read;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            read = in.readObject();
        }
        CONTEXT.remove();
        long[] found = await(seen(SecuritySinks.KIND_DESERIALIZATION, Cart.class.getName()));
        String others = found == null ? null : string(found[SideEffects.R_FLAGS] >>> 32);
        check(
                "an unfiltered deserialization records its top class, the classes read, and its call site (" + others
                        + ", " + describe() + ")",
                read instanceof Cart
                        && found != null
                        && found[SideEffects.R_REQUEST] == REQUEST_BITS
                        && found[SideEffects.R_COUNT] == 1
                        && others != null
                        && others.contains("java.util.ArrayList")
                        && others.contains("java.lang.Integer")
                        && outside(found).equals("bootuiagentit.SecurityChecksBehaviors#unfilteredDeserialization"));
    }

    static void filteredDeserialization() throws Exception {
        drain();
        RECORDS.clear();
        byte[] bytes = serialize(new Cart("ada", new ArrayList<>(List.of(1))));
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            in.setObjectInputFilter(info -> ObjectInputFilter.Status.ALLOWED);
            in.readObject();
        }
        Thread.sleep(50);
        drain();
        check("a stream with a filter records nothing (" + describe() + ")", checks().isEmpty());
    }

    static void deserializationOff() throws Exception {
        drain();
        RECORDS.clear();
        byte[] bytes = serialize(new Cart("ada", new ArrayList<>(List.of(1))));
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            in.readObject();
        }
        Thread.sleep(50);
        drain();
        check(
                "with the deserialization group off, an unfiltered read records nothing (" + describe() + ")",
                checks().stream()
                        .noneMatch(record -> record[SideEffects.R_KIND] == SecuritySinks.KIND_DESERIALIZATION));
    }

    /** A stream built while {@code -Djdk.serialFilter} sets a JVM-wide filter has it: its read records nothing. */
    static void jvmWideFilter() throws Exception {
        drain();
        RECORDS.clear();
        byte[] bytes = serialize(new Cart("ada", new ArrayList<>(List.of(1))));
        Object read;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            read = in.readObject();
        }
        Thread.sleep(50);
        drain();
        check(
                "a read under a JVM-wide jdk.serialFilter records nothing ("
                        + ObjectInputFilter.Config.getSerialFilter() + ", " + describe() + ")",
                read instanceof Cart && ObjectInputFilter.Config.getSerialFilter() != null && checks().isEmpty());
    }

    static void trustManager() throws Exception {
        drain();
        RECORDS.clear();
        SSLContext.getInstance("TLS").init(null, new javax.net.ssl.TrustManager[] {new TrustEverything()}, null);
        SSLContext.getInstance("TLS").init(null, null, null);
        long[] found = await(seen(SecuritySinks.KIND_TRUST_MANAGER, TrustEverything.class.getName()));
        Thread.sleep(50);
        drain();
        check(
                "an application trust manager passed to SSLContext.init is recorded, the JDK's default is not ("
                        + describe() + ")",
                found != null
                        && checks().size() == 1
                        && outside(found).equals("bootuiagentit.SecurityChecksBehaviors#trustManager"));
    }

    static void defaultHostnameVerifier() throws Exception {
        drain();
        RECORDS.clear();
        HostnameVerifier previous = HttpsURLConnection.getDefaultHostnameVerifier();
        HostnameVerifier lenient = (host, session) -> true;
        HttpsURLConnection.setDefaultHostnameVerifier(lenient);
        HttpsURLConnection.setDefaultHostnameVerifier(previous);
        long[] found =
                await(seen(SecuritySinks.KIND_HOSTNAME_VERIFIER, "bootuiagentit.SecurityChecksBehaviors$$Lambda"));
        check(
                "the application's default hostname verifier is recorded with its lambda's class (" + describe() + ")",
                found != null);
    }

    static void releaseRestores() throws Exception {
        AgentBridge.release("security-checks-behaviors", "dev");
        Object state = SideEffectsBehaviors.awaitState(SideEffects.SECURITY_SINKS, "released");
        SideEffects.beginSelfTest();
        try {
            try {
                MessageDigest.getInstance((String) null);
            } catch (NullPointerException expected) {
                // The point is whether the hook still runs.
            }
            SSLContext.getInstance("TLS").init(null, null, null);
        } finally {
            Map<String, Object> hits = SideEffects.endSelfTest();
            check(
                    "release restores MessageDigest and SSLContext (" + state + ", " + hits + ")",
                    "released".equals(state)
                            && Long.valueOf(0L).equals(hits.get("MessageDigest.getInstance"))
                            && Long.valueOf(0L).equals(hits.get("SSLContext.init")));
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    static byte[] serialize(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    static List<long[]> checks() {
        return RECORDS.stream()
                .filter(record -> record[SideEffects.R_SENSOR] == SideEffects.SENSOR_SECURITY_SINKS
                        && record[SideEffects.R_KIND] >= SecuritySinks.KIND_DESERIALIZATION)
                .toList();
    }

    static Predicate<long[]> matches(int kind, String target) {
        return record -> record[SideEffects.R_SENSOR] == SideEffects.SENSOR_SECURITY_SINKS
                && record[SideEffects.R_KIND] == kind
                && target.equals(string(record[SideEffects.R_TARGET]));
    }

    static Predicate<List<long[]>> seen(int kind, String target) {
        Predicate<long[]> one = matches(kind, target);
        return records -> records.stream().anyMatch(one);
    }

    static long[] await(Predicate<List<long[]>> done) throws Exception {
        for (int i = 0; i < 200 && !done.test(RECORDS); i++) {
            drain();
            if (!done.test(RECORDS)) {
                Thread.sleep(25);
            }
        }
        for (long[] record : RECORDS) {
            if (done.test(List.of(record))) {
                return record;
            }
        }
        return null;
    }

    static void drain() {
        SideEffects.flushThread();
        SideEffects.drain(token, record -> RECORDS.add(record.clone()));
    }

    static String describe() {
        StringBuilder text = new StringBuilder();
        for (long[] record : checks()) {
            text.append(record[SideEffects.R_KIND])
                    .append(' ')
                    .append(string(record[SideEffects.R_TARGET]))
                    .append(" x")
                    .append(record[SideEffects.R_COUNT])
                    .append(" origin ")
                    .append(origin(record))
                    .append(" at ")
                    .append(outside(record))
                    .append(" / ")
                    .append(application(record))
                    .append("; ");
        }
        return text.toString();
    }

    static int origin(long[] record) {
        return (int) (record[SideEffects.R_FLAGS] & 0x3);
    }

    static String outside(long[] record) {
        return FilesEnvironmentBehaviors.frame((int) (record[SideEffects.R_FRAMES] >>> 32));
    }

    static String application(long[] record) {
        return FilesEnvironmentBehaviors.frame((int) record[SideEffects.R_FRAMES]);
    }

    static String string(long id) {
        return FilesEnvironmentBehaviors.string(id);
    }

    static void check(String name, boolean ok) {
        RESULTS.add((ok ? "  PASS " : "  FAIL ") + name);
    }
}
