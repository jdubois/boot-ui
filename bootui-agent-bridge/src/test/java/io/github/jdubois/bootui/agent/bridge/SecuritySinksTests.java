package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.checks.Shop;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The security-sinks sensor's JDK checks on the bridge side (PLAN-v2 §5.16, M5-6b2): the allocation-free algorithm
 * checks, who asked, the outermost unfiltered deserialization with the classes it resolved, trust managers and
 * defaults, the per-group switches, and the request-value seam on the sensor's bit.
 */
class SecuritySinksTests {

    private static final String REQUEST = "00000000000000ab";
    private static final long REQUEST_BITS = 0xabL;

    private final List<Object> keep = new ArrayList<>();

    @BeforeEach
    void install() {
        AgentBridge.reset();
        AgentBridge.install(request -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "ok");
            return answer;
        });
    }

    @AfterEach
    void reset() {
        AgentBridge.reset();
    }

    // ---- the fast checks ----------------------------------------------------------------------------------------

    @Test
    void weakDigestsAreToldApartFromStrongOnesCaseInsensitively() {
        for (String weak :
                List.of("MD5", "md5", "MD2", "SHA-1", "sha1", "SHA", "OID.1.3.14.3.2.26", "1.2.840.113549.2.5")) {
            assertThat(SecuritySinks.weakDigest(weak)).as(weak).isTrue();
        }
        for (String strong : List.of("SHA-256", "SHA-512", "SHA3-256", "SHA-224", "MD5x", "", "SHA-", "OID.")) {
            assertThat(SecuritySinks.weakDigest(strong)).as(strong).isFalse();
        }
        assertThat(SecuritySinks.weakDigest(null)).isFalse();
    }

    @Test
    void weakCiphersAreDesRc4AndBlockCiphersInEcbModeIncludingTheirBareNames() {
        for (String weak : List.of(
                "DES",
                "DES/CBC/PKCS5Padding",
                "DESede/CBC/PKCS5Padding",
                "TripleDES",
                "RC4",
                "ARCFOUR",
                "AES",
                "aes",
                "AES_256",
                "AES/ECB/PKCS5Padding",
                " AES / ECB / NoPadding",
                "Blowfish",
                "Blowfish/ECB/PKCS5Padding",
                "RC2/ECB/NoPadding",
                "AES/ECB")) {
            assertThat(SecuritySinks.weakCipher(weak)).as(weak).isTrue();
        }
        for (String strong : List.of(
                "AES/GCM/NoPadding",
                "AES/CBC/PKCS5Padding",
                "AES_128/GCM/NoPadding",
                "ChaCha20-Poly1305",
                "RSA/ECB/PKCS1Padding",
                "RSA/ECB/OAEPWithSHA-256AndMGF1Padding",
                "PBEWithHmacSHA256AndAES_256",
                "",
                "AESX")) {
            assertThat(SecuritySinks.weakCipher(strong)).as(strong).isFalse();
        }
        assertThat(SecuritySinks.weakCipher(null)).isFalse();
    }

    // ---- weak algorithms ----------------------------------------------------------------------------------------

    @Test
    void aWeakDigestTheApplicationAsksForIsRecordedWithItsCallSiteAndAStrongOneIsNot() {
        long token = enabledClaim(SecuritySinks.GROUPS);

        Shop.hash("SHA-256");
        Shop.encrypt("AES/GCM/NoPadding");
        Shop.hash("MD5");
        Shop.encrypt("AES/ECB/PKCS5Padding");

        List<long[]> records = drain(token);
        assertThat(records).hasSize(2);
        long[] digest = records.get(0);
        assertThat(digest[SideEffects.R_SENSOR]).isEqualTo(SideEffects.SENSOR_SECURITY_SINKS);
        assertThat(digest[SideEffects.R_KIND]).isEqualTo(SecuritySinks.KIND_WEAK_DIGEST);
        assertThat(string(digest[SideEffects.R_TARGET])).isEqualTo("MD5");
        assertThat(outcome(digest)).isEqualTo(SecuritySinks.ORIGIN_APPLICATION);
        assertThat(outside(digest)).isEqualTo("com.example.checks.Shop#hash");
        assertThat(application(digest)).isEqualTo("com.example.checks.Shop#hash");
        assertThat(digest[SideEffects.R_REQUEST]).isEqualTo(REQUEST_BITS);
        long[] cipher = records.get(1);
        assertThat(cipher[SideEffects.R_KIND]).isEqualTo(SecuritySinks.KIND_WEAK_CIPHER);
        assertThat(string(cipher[SideEffects.R_TARGET])).isEqualTo("AES/ECB/PKCS5Padding");
        assertThat(SideEffects.status(SideEffects.SECURITY_SINKS)).containsEntry("weakAlgorithms", 2L);
    }

    @Test
    void aLibrarysRequestIsReportedApartWithTheApplicationFrameAboveIt() {
        long token = enabledClaim(SecuritySinks.GROUPS);

        Shop.hashThroughLibrary("SHA1");
        // A second time, from the same call site: the attribution is remembered, the record the same.
        Shop.hashThroughLibrary("SHA1");

        List<long[]> records = drain(token);
        assertThat(records).hasSize(2);
        for (long[] record : records) {
            assertThat(outcome(record)).isEqualTo(SecuritySinks.ORIGIN_LIBRARY);
            assertThat(outside(record)).isEqualTo("org.acme.crypto.Digests#digest");
            assertThat(application(record)).isEqualTo("com.example.checks.Shop#hashThroughLibrary");
            assertThat(record[SideEffects.R_FRAMES]).isEqualTo(records.get(0)[SideEffects.R_FRAMES]);
        }
    }

    @Test
    void aRememberedLibraryCallerStillNamesTheApplicationFrameThatCalledIt() {
        long token = enabledClaim(SecuritySinks.GROUPS);

        Shop.hashThroughLibrary("MD5");
        Shop.etag("MD5");

        List<long[]> records = drain(token);
        assertThat(records).hasSize(2);
        assertThat(records)
                .allSatisfy(record -> assertThat(outside(record)).isEqualTo("org.acme.crypto.Digests#digest"));
        assertThat(application(records.get(0))).isEqualTo("com.example.checks.Shop#hashThroughLibrary");
        assertThat(application(records.get(1))).isEqualTo("com.example.checks.Shop#etag");
        assertThat(SideEffects.status(SideEffects.SECURITY_SINKS).get("recorded"))
                .isEqualTo(Map.of(
                        "MessageDigest.getInstance", 2L,
                        "Cipher.getInstance", 0L,
                        "ObjectInputStream.readObject", 0L,
                        "ObjectInputStream.resolveClass", 0L,
                        "SSLContext.init", 0L,
                        "HttpsURLConnection.setDefaultHostnameVerifier", 0L,
                        "HttpsURLConnection.setDefaultSSLSocketFactory", 0L));
    }

    @Test
    void aGroupsErrorBudgetSwitchesThatGroupOffAloneAndNeverRequestValueMatching() {
        enabledClaim(SecuritySinks.GROUPS);
        for (int i = 0; i < SideEffects.MAX_ERRORS; i++) {
            SecuritySinks.failed(SecuritySinks.GROUP_ALGORITHMS, new IllegalStateException("check " + i));
        }
        SecuritySinks.failed(SecuritySinks.GROUP_TRUST, new IllegalStateException("one"));

        int others = SecuritySinks.GROUP_DESERIALIZATION | SecuritySinks.GROUP_TRUST;
        assertThat(SecuritySinks.groups).isEqualTo(others);
        SecuritySinks.groups(SecuritySinks.GROUPS, null);
        assertThat(SecuritySinks.groups).as("off for the JVM's life, alone").isEqualTo(others);
        assertThat(SideEffects.mask & SideEffects.MASK_SECURITY_SINKS).isNotZero();
        assertThat(RequestValues.active()).isTrue();
        Map<String, Object> status = SideEffects.status(SideEffects.SECURITY_SINKS);
        assertThat(status.get("checkErrors"))
                .isEqualTo(Map.of("deserialization", 0L, "weak-algorithms", 100L, "trust-managers", 1L));
        @SuppressWarnings("unchecked")
        Map<String, Object> groups = (Map<String, Object>) status.get("groups");
        assertThat(groups).containsEntry("deserialization", "on").containsEntry("trust-managers", "on");
        assertThat(groups.get("weak-algorithms").toString()).startsWith("off: switched off after 100 internal errors");
        Shop.hash("MD5");
        Shop.trust(new Object[] {new Shop.TrustAll()});
        assertThat(drain(enabledToken))
                .singleElement()
                .satisfies(
                        record -> assertThat(record[SideEffects.R_KIND]).isEqualTo(SecuritySinks.KIND_TRUST_MANAGER));
    }

    @Test
    void aLibraryInAComSunPackageIsNotTheJdkAndTheJdksOwnComSunClassesAre() {
        assertThat(SecuritySinks.jdk(classFrame(com.sun.faces.renderkit.ClientSideStateHelper.class)))
                .as("a com.sun class of the class path")
                .isFalse();
        assertThat(SecuritySinks.jdk(classFrame(java.util.UUID.class))).isTrue();
        assertThat(SecuritySinks.jdk(classFrame(javax.crypto.Cipher.class))).isTrue();
        assertThat(SecuritySinks.jdk(classFrame(java.sql.Connection.class)))
                .as("a platform module's class")
                .isTrue();
        assertThat(SecuritySinks.jdk(classFrame(Shop.class))).isFalse();
        // By name only, when the walker keeps no class.
        assertThat(SecuritySinks.jdk(frame("com.sun.faces.renderkit.ClientSideStateHelper", "getState")))
                .isFalse();
        assertThat(SecuritySinks.jdk(frame("com.sun.mail.util.MailSSLSocketFactory", "init")))
                .isFalse();
        assertThat(SecuritySinks.jdk(frame("com.sun.crypto.provider.CipherCore", "init")))
                .isTrue();
        assertThat(SecuritySinks.jdk(frame("sun.security.provider.SecureRandom", "init")))
                .isTrue();
    }

    @Test
    void anImmediateCallerInTheJdkIsTheJdksOwnUseAndIsNotAttributed() {
        enabledClaim(SecuritySinks.GROUPS);
        Claim claim = AgentBridge.current();

        long[] jdk = new SecuritySinks.Attribution(claim, SideEffects.HOOK_DIGEST, "java.security.MessageDigest", 1)
                .apply(Stream.of(
                        frame("io.github.jdubois.bootui.agent.bridge.SecuritySinks", "digest"),
                        frame("java.security.MessageDigest", "getInstance"),
                        frame("sun.security.provider.SecureRandom", "init"),
                        frame("com.example.checks.Shop", "token")));
        long[] reflective = new SecuritySinks.Attribution(
                        claim, SideEffects.HOOK_DIGEST, "java.security.MessageDigest", 2)
                .apply(Stream.of(
                        frame("java.security.MessageDigest", "getInstance"),
                        frame("jdk.internal.reflect.DirectMethodHandleAccessor", "invoke"),
                        frame("java.lang.reflect.Method", "invoke"),
                        frame("com.example.checks.Shop", "token")));
        long[] crypto = new SecuritySinks.Attribution(claim, SideEffects.HOOK_CIPHER, "javax.crypto.Cipher", 3)
                .apply(Stream.of(
                        frame("javax.crypto.Cipher", "getInstance"),
                        frame("javax.crypto.CipherSpi", "engineInit"),
                        frame("com.example.checks.Shop", "token")));

        assertThat(jdk).isNull();
        assertThat(reflective).isNotNull();
        assertThat(reflective[1]).isEqualTo(SecuritySinks.ORIGIN_APPLICATION);
        assertThat(crypto).isNull();
    }

    @Test
    void aSwitchedOffGroupRecordsNothingAndTheOthersStillDo() {
        long token = enabledClaim(SecuritySinks.GROUP_DESERIALIZATION | SecuritySinks.GROUP_TRUST);
        SecuritySinks.groups(
                SecuritySinks.GROUP_DESERIALIZATION | SecuritySinks.GROUP_TRUST,
                new String[] {null, "self-test failed for [MessageDigest.getInstance]", null});

        Shop.hash("MD5");
        Shop.trust(new Object[] {new Shop.TrustAll()});

        List<long[]> records = drain(token);
        assertThat(records).hasSize(1);
        assertThat(records.get(0)[SideEffects.R_KIND]).isEqualTo(SecuritySinks.KIND_TRUST_MANAGER);
        @SuppressWarnings("unchecked")
        Map<String, Object> groups = (Map<String, Object>)
                SideEffects.status(SideEffects.SECURITY_SINKS).get("groups");
        assertThat(groups)
                .containsEntry("deserialization", "on")
                .containsEntry("weak-algorithms", "off: self-test failed for [MessageDigest.getInstance]")
                .containsEntry("trust-managers", "on");
    }

    @Test
    void theSelfTestsThreadCountsEachHookAndRecordsNothing() throws Exception {
        long token = enabledClaim(SecuritySinks.GROUPS);

        SideEffects.beginSelfTest();
        try {
            Shop.hash(null);
            Shop.encrypt("");
            Shop.trust(null);
            Shop.verifier(null);
            Shop.factory(null);
            assertThat(Shop.reading(null)).isZero();
            SecuritySinks.resolved(Integer.class);
        } finally {
            Map<String, Object> hits = SideEffects.endSelfTest();
            for (String hook : List.of(
                    "MessageDigest.getInstance",
                    "Cipher.getInstance",
                    "SSLContext.init",
                    "HttpsURLConnection.setDefaultHostnameVerifier",
                    "HttpsURLConnection.setDefaultSSLSocketFactory",
                    "ObjectInputStream.readObject",
                    "ObjectInputStream.resolveClass")) {
                assertThat(hits.get(hook)).as(hook).isEqualTo(1L);
            }
        }
        assertThat(drain(token)).isEmpty();
    }

    @Test
    void anotherOpenSideEffectHookSilencesTheChecks() {
        long token = enabledClaim(SecuritySinks.GROUPS);
        CodePaths.Frame frame = CodePaths.frame();
        frame.sideEffectOpen = SideEffects.MASK_FILES;
        frame.sideEffectSince = System.nanoTime();
        try {
            Shop.hash("MD5");
        } finally {
            frame.sideEffectOpen = 0;
        }
        Shop.hash("MD5");

        assertThat(drain(token)).hasSize(1);
        assertThat(frame.sideEffectOpen).isZero();
    }

    @Test
    void nothingIsRecordedWhileTheSensorIsNotEnabled() {
        long token = claim(List.of(SideEffects.SECURITY_SINKS));
        SecuritySinks.groups(SecuritySinks.GROUPS, null);

        Shop.hash("MD5");
        assertThat(Shop.reading(stream(List.of(1)))).isZero();

        assertThat(drain(token)).isEmpty();
    }

    // ---- deserialization ----------------------------------------------------------------------------------------

    @Test
    void anUnfilteredOutermostReadIsRecordedOnceWithItsTopClassAndTheOthersSorted() throws Exception {
        long token = enabledClaim(SecuritySinks.GROUPS);
        ObjectInputStream stream = stream(List.of(1));

        long outer = Shop.reading(stream);
        SecuritySinks.resolved(ArrayList.class);
        long nested = Shop.reading(stream);
        SecuritySinks.resolved(Integer.class);
        SecuritySinks.resolved(Number.class);
        SecuritySinks.resolved(Integer.class);
        Shop.read(nested, null);
        Shop.read(outer, null);

        assertThat(outer).isGreaterThan(SecuritySinks.NESTED);
        assertThat(nested).isEqualTo(SecuritySinks.NESTED);
        List<long[]> records = drain(token);
        assertThat(records).hasSize(1);
        long[] record = records.get(0);
        assertThat(record[SideEffects.R_KIND]).isEqualTo(SecuritySinks.KIND_DESERIALIZATION);
        assertThat(string(record[SideEffects.R_TARGET])).isEqualTo("java.util.ArrayList");
        assertThat(string(record[SideEffects.R_FLAGS] >>> 32)).isEqualTo("java.lang.Integer, java.lang.Number");
        assertThat(outcome(record)).isEqualTo(SecuritySinks.ORIGIN_APPLICATION);
        assertThat(outside(record)).isEqualTo("com.example.checks.Shop#read");
        assertThat(CodePaths.frame().serial.depth).isZero();
        assertThat(CodePaths.frame().serial.names).containsOnlyNulls();
    }

    @Test
    void aFilteredStreamIsNotTrackedAndAReadThatThrowsIsFlagged() throws Exception {
        long token = enabledClaim(SecuritySinks.GROUPS);
        ObjectInputStream filtered = stream(List.of(1));
        filtered.setObjectInputFilter(info -> ObjectInputFilter.Status.ALLOWED);

        assertThat(Shop.reading(filtered)).isZero();
        SecuritySinks.resolved(ArrayList.class);
        long outer = Shop.reading(stream(List.of(2)));
        Shop.read(outer, new java.io.InvalidClassException("refused"));

        List<long[]> records = drain(token);
        assertThat(records).hasSize(1);
        assertThat(outcome(records.get(0)) & SecuritySinks.FLAG_ERROR).isNotZero();
        assertThat(records.get(0)[SideEffects.R_TARGET]).isZero();
        assertThat(SideEffects.status(SideEffects.SECURITY_SINKS))
                .containsEntry("filteredDeserializations", 1L)
                .containsEntry("classesNotNamed", 1L);
    }

    @Test
    void aFilteredReadOnAFreshThreadAllocatesNoThreadState() throws Exception {
        enabledClaim(SecuritySinks.GROUPS);
        ObjectInputStream filtered = stream(List.of(1));
        filtered.setObjectInputFilter(info -> ObjectInputFilter.Status.ALLOWED);
        java.util.concurrent.atomic.AtomicReference<Object> state = new java.util.concurrent.atomic.AtomicReference<>();
        Thread thread = new Thread(() -> {
            Shop.reading(filtered);
            state.set(CodePaths.FRAME.get() == null ? "none" : "allocated");
        });
        thread.start();
        thread.join(10_000L);

        assertThat(state.get()).isEqualTo("none");
    }

    @Test
    void aStreamsOwnNestedReadsReturnAtOnceAndAFilteredStreamCountsOncePerRead() throws Exception {
        long token = enabledClaim(SecuritySinks.GROUPS);
        ObjectInputStream filtered = stream(List.of(1));
        filtered.setObjectInputFilter(info -> ObjectInputFilter.Status.ALLOWED);

        // A filtered HashMap of three entries: one outermost read, three nested ones on the same stream.
        assertThat(Shop.reading(filtered)).isZero();
        for (int depth = 1; depth <= 3; depth++) {
            assertThat(Shop.readingNested(filtered, depth)).isZero();
        }
        // The same shape unfiltered: one tracked read, its nested entries untouched, one record.
        ObjectInputStream unfiltered = stream(List.of(2));
        long outer = Shop.reading(unfiltered);
        int before = CodePaths.frame().serial.depth;
        for (int depth = 1; depth <= 3; depth++) {
            assertThat(Shop.readingNested(unfiltered, depth)).isZero();
        }
        SecuritySinks.resolved(java.util.HashMap.class);
        Shop.read(outer, null);

        assertThat(before).isEqualTo(1);
        assertThat(SideEffects.status(SideEffects.SECURITY_SINKS)).containsEntry("filteredDeserializations", 1L);
        assertThat(drain(token))
                .singleElement()
                .satisfies(record ->
                        assertThat(string(record[SideEffects.R_TARGET])).isEqualTo("java.util.HashMap"));
    }

    @Test
    void aJdkVerdictIsRememberedPerCallerSoItsClassIsNotAskedAgain() {
        enabledClaim(SecuritySinks.GROUPS);
        Claim claim = AgentBridge.current();
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < 3; i++) {
            long[] who = new SecuritySinks.Attribution(
                            claim, SideEffects.HOOK_DIGEST, "java.security.MessageDigest", "MD5".hashCode())
                    .apply(Stream.of(
                            frame("java.security.MessageDigest", "getInstance"),
                            countingFrame(java.util.UUID.class, "nameUUIDFromBytes", asked)));
            assertThat(who).isNull();
        }

        assertThat(asked.get())
                .as("the caller's class is asked once, then remembered")
                .isEqualTo(1);
        assertThat(SideEffects.status(SideEffects.SECURITY_SINKS)).containsEntry("jdkRequests", 3L);
    }

    @Test
    void knownLibraryTrustAllManagersAreRecordedThroughTheirWrappersAndOthersAreNot() {
        long token = enabledClaim(SecuritySinks.GROUPS);
        Object netty = io.netty.handler.ssl.util.InsecureTrustManagerFactory.TRUST_MANAGER;

        Shop.trust(new Object[] {new io.netty.handler.ssl.EnhancingX509ExtendedTrustManager(netty)});
        // Netty 4.1's shape: its insecure trust manager inside X509TrustManagerWrapper, inside the resumption wrapper.
        Shop.trust(new Object[] {new io.netty.handler.ssl.util.X509TrustManagerWrapper(netty)});
        Shop.trust(new Object[] {
            new org.apache.hc.core5.ssl.SSLContextBuilder.TrustManagerDelegate(
                    new Object(), org.apache.hc.client5.http.ssl.TrustAllStrategy.INSTANCE)
        });
        // A wrapper around a trust manager that checks: nothing.
        Shop.trust(new Object[] {new io.netty.handler.ssl.EnhancingX509ExtendedTrustManager(new Object())});
        Shop.trust(
                new Object[] {new org.apache.hc.core5.ssl.SSLContextBuilder.TrustManagerDelegate(new Object(), null)});

        // The application's own strategy inside Apache's delegate: the application's trust manager.
        Shop.trust(new Object[] {
            new org.apache.hc.core5.ssl.SSLContextBuilder.TrustManagerDelegate(new Object(), new Shop.TrustAll())
        });

        List<long[]> records = drain(token);
        assertThat(records).hasSize(4);
        assertThat(records.subList(0, 3)).allSatisfy(record -> {
            assertThat(record[SideEffects.R_KIND]).isEqualTo(SecuritySinks.KIND_TRUST_ALL);
            assertThat(outcome(record)).isEqualTo(SecuritySinks.ORIGIN_APPLICATION);
        });
        assertThat(string(records.get(0)[SideEffects.R_TARGET]))
                .isEqualTo("io.netty.handler.ssl.util.InsecureTrustManagerFactory");
        assertThat(string(records.get(1)[SideEffects.R_TARGET]))
                .isEqualTo("io.netty.handler.ssl.util.InsecureTrustManagerFactory");
        assertThat(string(records.get(2)[SideEffects.R_TARGET]))
                .isEqualTo("org.apache.hc.client5.http.ssl.TrustAllStrategy");
        assertThat(records.get(3)[SideEffects.R_KIND]).isEqualTo(SecuritySinks.KIND_TRUST_MANAGER);
        assertThat(string(records.get(3)[SideEffects.R_TARGET])).isEqualTo("com.example.checks.Shop$TrustAll");
        assertThat(SideEffects.status(SideEffects.SECURITY_SINKS)).containsEntry("trustAllManagers", 3L);
    }

    @Test
    void trustAllNamesMatchExactlyOrTheirNestedClasses() {
        assertThat(SecuritySinks.trustAllName("io.vertx.core.net.impl.TrustAllTrustManager"))
                .isEqualTo("io.vertx.core.net.impl.TrustAllTrustManager");
        assertThat(SecuritySinks.trustAllName("io.netty.handler.ssl.util.InsecureTrustManagerFactory$1"))
                .isEqualTo("io.netty.handler.ssl.util.InsecureTrustManagerFactory");
        assertThat(SecuritySinks.trustAllName("org.apache.http.conn.ssl.TrustSelfSignedStrategy"))
                .isEqualTo("org.apache.http.conn.ssl.TrustSelfSignedStrategy");
        assertThat(SecuritySinks.trustAllName("io.vertx.core.net.impl.TrustAllTrustManagerFactory"))
                .isNull();
        assertThat(SecuritySinks.trustAllName("io.netty.handler.ssl.util.InsecureTrustManagerFactoryX"))
                .isNull();
        assertThat(SecuritySinks.trustAllName("sun.security.ssl.X509TrustManagerImpl"))
                .isNull();
    }

    @Test
    void theOutermostExitClosesTheReadEvenWhenANestedExitWasLost() throws Exception {
        long token = enabledClaim(SecuritySinks.GROUPS);
        ObjectInputStream stream = stream(List.of(1));

        long outer = Shop.reading(stream);
        Shop.reading(stream);
        Shop.reading(stream);
        // Both nested exits lost, as to a stack overflow: the outermost still ends the read.
        Shop.read(outer, new StackOverflowError());
        long next = Shop.reading(stream);

        assertThat(next).isGreaterThan(SecuritySinks.NESTED);
        Shop.read(next, null);
        assertThat(drain(token)).hasSize(2);
    }

    @Test
    void atMostSixteenClassesAreNamed() throws Exception {
        long token = enabledClaim(SecuritySinks.GROUPS);
        long outer = Shop.reading(stream(List.of(1)));
        Class<?>[] classes = {
            Object.class,
            String.class,
            Integer.class,
            Long.class,
            Short.class,
            Byte.class,
            Double.class,
            Float.class,
            Character.class,
            Boolean.class,
            Number.class,
            ArrayList.class,
            LinkedHashMap.class,
            List.class,
            Map.class,
            Arrays.class,
            Function.class,
            Supplier.class
        };
        for (Class<?> type : classes) {
            SecuritySinks.resolved(type);
        }
        Shop.read(outer, null);

        long[] record = drain(token).get(0);
        String others = string(record[SideEffects.R_FLAGS] >>> 32);
        assertThat(others).endsWith(", (more)");
        assertThat(others.split(", ")).hasSize(SecuritySinks.MAX_CLASSES);
    }

    // ---- trust managers and defaults ----------------------------------------------------------------------------

    @Test
    void anApplicationsTrustManagerIsRecordedAndAJdkOneIsNot() throws Exception {
        long token = enabledClaim(SecuritySinks.GROUPS);

        Shop.trust(new Object[] {new Object()});
        Shop.trust(new Object[] {null, new Shop.TrustAll()});

        List<long[]> records = drain(token);
        assertThat(records).hasSize(1);
        assertThat(records.get(0)[SideEffects.R_KIND]).isEqualTo(SecuritySinks.KIND_TRUST_MANAGER);
        assertThat(string(records.get(0)[SideEffects.R_TARGET])).isEqualTo("com.example.checks.Shop$TrustAll");
        assertThat(outside(records.get(0))).isEqualTo("com.example.checks.Shop#trust");
    }

    @Test
    void aDefaultTheApplicationInstallsIsRecordedWithItsLambdaNamedAndALibrarysIsOnlyCounted() {
        long token = enabledClaim(SecuritySinks.GROUPS);
        Object lambda = Shop.lambda();

        Shop.verifier(lambda);
        Shop.factory(new Shop.TrustAll());
        Shop.verifierThroughLibrary(lambda);

        List<long[]> records = drain(token);
        assertThat(records).hasSize(2);
        assertThat(records.get(0)[SideEffects.R_KIND]).isEqualTo(SecuritySinks.KIND_HOSTNAME_VERIFIER);
        assertThat(string(records.get(0)[SideEffects.R_TARGET])).isEqualTo("com.example.checks.Shop$$Lambda");
        assertThat(records.get(1)[SideEffects.R_KIND]).isEqualTo(SecuritySinks.KIND_SOCKET_FACTORY);
        assertThat(SideEffects.status(SideEffects.SECURITY_SINKS)).containsEntry("libraryDefaults", 1L);
    }

    @Test
    void classNamesAndAlgorithmsAreShownSanitizedAndBounded() {
        assertThat(SecuritySinks.className("com.example.A$$Lambda/0x0000000800c03000"))
                .isEqualTo("com.example.A$$Lambda");
        assertThat(SecuritySinks.className("com.example.A$$Lambda$14/0x0000000800c03000"))
                .isEqualTo("com.example.A$$Lambda");
        assertThat(SecuritySinks.className("com.example.Hidden/0x1")).isEqualTo("com.example.Hidden");
        assertThat(SecuritySinks.className("x".repeat(500))).hasSize(SecuritySinks.MAX_NAME);
        assertThat(SecuritySinks.algorithmText("AES/ECB/PKCS5Padding")).isEqualTo("AES/ECB/PKCS5Padding");
        assertThat(SecuritySinks.algorithmText("MD5\n<x>")).isEqualTo("MD5??x?");
    }

    // ---- the request-value seam -----------------------------------------------------------------------------------

    @Test
    void requestValueMatchingFollowsTheSensorsBitNotItsChecks() {
        claim(List.of(SideEffects.SECURITY_SINKS));
        assertThat(RequestValues.sensorGeneration()).isEqualTo(Long.MIN_VALUE);

        SideEffects.enable(SideEffects.MASK_SECURITY_SINKS);
        assertThat(RequestValues.sensorGeneration()).isEqualTo(generation());
        assertThat(RequestValues.active()).isTrue();
        // Every check group off: matching stays on.
        SecuritySinks.groups(0, new String[] {"a", "b", "c"});
        assertThat(RequestValues.active()).isTrue();

        SideEffects.disable(SideEffects.MASK_SECURITY_SINKS, null);
        assertThat(RequestValues.sensorGeneration()).isEqualTo(Long.MIN_VALUE);
        assertThat(RequestValues.active()).isFalse();
    }

    @Test
    void theSecuritySinksSensorIsReportedBesideTheOthers() {
        assertThat(SideEffects.sensorIds())
                .contains(SideEffects.SECURITY_SINKS, SideEffects.BLOCKING, SideEffects.THREAD_ACTIVITY);
        assertThat(SideEffects.bit(SideEffects.SECURITY_SINKS)).isEqualTo(SideEffects.MASK_SECURITY_SINKS);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private long enabledToken;

    private long enabledClaim(int groups) {
        long token = claim(List.of(SideEffects.SECURITY_SINKS));
        enabledToken = token;
        SideEffects.enable(SideEffects.MASK_SECURITY_SINKS);
        SecuritySinks.groups(groups, null);
        return token;
    }

    private long claim(List<String> sensors) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "shop");
        request.put("mode", "dev");
        request.put("packages", List.of("com.example"));
        request.put("sensors", sensors);
        Supplier<Object> capture = () -> new Object[] {REQUEST, null, null, null, null, null, null, 1L, 1L};
        Function<Object, AutoCloseable> reopen = snapshot -> null;
        keep.add(capture);
        keep.add(reopen);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        assertThat(result.get("status")).isEqualTo(AgentBridge.ARMED);
        return (Long) result.get("token");
    }

    private static ObjectInputStream stream(Object value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(new ArrayList<>((List<?>) value));
            }
            return new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
    }

    /** A frame whose walker retains its class. */
    private static StackWalker.StackFrame classFrame(Class<?> type) {
        StackWalker.StackFrame named = frame(type.getName(), "run");
        return new StackWalker.StackFrame() {
            @Override
            public String getClassName() {
                return named.getClassName();
            }

            @Override
            public String getMethodName() {
                return named.getMethodName();
            }

            @Override
            public Class<?> getDeclaringClass() {
                return type;
            }

            @Override
            public int getByteCodeIndex() {
                return 0;
            }

            @Override
            public String getFileName() {
                return null;
            }

            @Override
            public int getLineNumber() {
                return -1;
            }

            @Override
            public boolean isNativeMethod() {
                return false;
            }

            @Override
            public StackTraceElement toStackTraceElement() {
                return named.toStackTraceElement();
            }
        };
    }

    /** A frame retaining its class, counting how often the class is asked for. */
    private static StackWalker.StackFrame countingFrame(
            Class<?> type, String method, java.util.concurrent.atomic.AtomicInteger asked) {
        StackWalker.StackFrame named = frame(type.getName(), method);
        return new StackWalker.StackFrame() {
            @Override
            public String getClassName() {
                return named.getClassName();
            }

            @Override
            public String getMethodName() {
                return method;
            }

            @Override
            public Class<?> getDeclaringClass() {
                asked.incrementAndGet();
                return type;
            }

            @Override
            public int getByteCodeIndex() {
                return 0;
            }

            @Override
            public String getFileName() {
                return null;
            }

            @Override
            public int getLineNumber() {
                return -1;
            }

            @Override
            public boolean isNativeMethod() {
                return false;
            }

            @Override
            public StackTraceElement toStackTraceElement() {
                return named.toStackTraceElement();
            }
        };
    }

    private static StackWalker.StackFrame frame(String className, String method) {
        return new StackWalker.StackFrame() {
            @Override
            public String getClassName() {
                return className;
            }

            @Override
            public String getMethodName() {
                return method;
            }

            @Override
            public Class<?> getDeclaringClass() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int getByteCodeIndex() {
                return 0;
            }

            @Override
            public String getFileName() {
                return null;
            }

            @Override
            public int getLineNumber() {
                return -1;
            }

            @Override
            public boolean isNativeMethod() {
                return false;
            }

            @Override
            public StackTraceElement toStackTraceElement() {
                return new StackTraceElement(className, method, null, -1);
            }

            public MethodType getMethodType() {
                throw new UnsupportedOperationException();
            }

            public String getDescriptor() {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static long generation() {
        return (Long) ((Map<?, ?>) AgentBridge.status().get("claim")).get("generation");
    }

    private static int outcome(long[] record) {
        return (int) (record[SideEffects.R_FLAGS] & 0xFF);
    }

    private static String outside(long[] record) {
        return string(record[SideEffects.R_FRAMES] >>> 32);
    }

    private static String application(long[] record) {
        return string(record[SideEffects.R_FRAMES] & 0xFFFFFFFFL);
    }

    private static List<long[]> drain(long token) {
        List<long[]> records = new ArrayList<>();
        SideEffects.drain(token, record -> records.add(record.clone()));
        return records;
    }

    private static String string(long id) {
        if (id <= 0) {
            return null;
        }
        String[] strings = SideEffects.interned(generation(), (int) id);
        return strings == null || strings.length == 0 ? null : strings[0];
    }
}
