package io.github.jdubois.bootui.engine.sideeffects;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.SecuritySinks;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import io.github.jdubois.bootui.core.dto.SideEffectsRowDto;
import io.github.jdubois.bootui.core.dto.SideEffectsSensorDto;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentHandoffs;
import io.github.jdubois.bootui.engine.javaagent.AgentSensorSettings;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.journal.AgentEvidence;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sideeffectsapp.Launcher;

/**
 * The security-sinks sensor's JDK checks as Security sinks rows ({@code docs/PLAN-v2.md} §5.16, M5-6b2), against the
 * real bridge, with request-value matching off: a weak algorithm the application asks for, one a library asks for
 * (grouped as a library's), a deserialization without a filter with the classes it read merged per call site, and a
 * trust manager of the application, each worded as a fact, never as a weakness.
 */
class SecurityChecksServiceTests {

    private static final String REQUEST = "00000000000000ab";

    private final AtomicReference<CorrelationContext> context = new AtomicReference<>(CorrelationContext.NONE);
    private final AtomicLong clock = new AtomicLong();
    private final AgentEvidence evidence = new AgentEvidence(panel -> true, null);
    private SideEffectsService service;
    private AgentClaim claim;

    @BeforeEach
    void installAgent() {
        resetBridge();
        AgentBridge.install(request -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "ok");
            return answer;
        });
        clock.set(System.currentTimeMillis() - 60_000L);
    }

    @AfterEach
    void resetAgent() {
        if (service != null) {
            service.close();
        }
        if (claim != null) {
            claim.disarm();
        }
        resetBridge();
    }

    @Test
    void aWeakDigestTheApplicationAsksForIsARowWordedAsAFact() {
        start();
        inRequest(Launcher::md5);
        inRequest(Launcher::md5);

        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo(SideEffectsCatalog.WEAK_DIGEST);
            assertThat(row.target()).isEqualTo("MD5");
            assertThat(row.origin()).isEqualTo(SideEffectOrigins.APPLICATION);
            assertThat(row.callSite()).isEqualTo("sideeffectsapp.Launcher#md5");
            assertThat(row.parameter()).isNull();
            assertThat(row.count()).isEqualTo(2L);
            assertThat(row.attribution()).isEqualTo("GET /api/sample/hash");
            assertThat(row.detail())
                    .startsWith("Weak algorithm MD5 requested by application code at `sideeffectsapp.Launcher#md5`.")
                    .contains("MD5 and SHA-1 remain fine for checksums and ETags")
                    .doesNotContain(SinkWording.SEEN_ONCE)
                    .doesNotContainIgnoringCase("vulnerab")
                    .doesNotContainIgnoringCase("injection");
        });
    }

    @Test
    void aLibrarysRequestIsGroupedAsALibrarysWithTheApplicationFrameAboveIt() {
        start();
        inRequest(Launcher::sha1ThroughLibrary);

        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.target()).isEqualTo("SHA-1");
            assertThat(row.origin()).isEqualTo(SideEffectOrigins.LIBRARY);
            assertThat(row.location()).isEqualTo("sideeffectslibrary.Digests#sha1");
            assertThat(row.callSite()).isEqualTo("sideeffectsapp.Launcher#sha1ThroughLibrary");
            assertThat(row.detail())
                    .startsWith("Weak algorithm SHA-1 requested by library code `sideeffectslibrary.Digests#sha1` for"
                            + " application frame `sideeffectsapp.Launcher#sha1ThroughLibrary`.");
        });
    }

    @Test
    void deserializationsAtOneCallSiteShareARowAndMergeTheClassesTheyRead() throws Exception {
        start();
        inRequest(() -> Launcher.deserialize(stream(), false, ArrayList.class, Integer.class, Number.class));
        inRequest(() -> Launcher.deserialize(stream(), true, ArrayList.class, String.class));

        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo(SideEffectsCatalog.DESERIALIZATION);
            assertThat(row.target()).isEqualTo("java.util.ArrayList");
            assertThat(row.count()).isEqualTo(2L);
            assertThat(row.failed()).isEqualTo(1L);
            assertThat(row.callSite()).isEqualTo("sideeffectsapp.Launcher#deserialize");
            assertThat(row.detail())
                    .isEqualTo("Deserialization without an ObjectInputFilter at `sideeffectsapp.Launcher#deserialize`"
                            + " (classes read: java.util.ArrayList, java.lang.Integer, java.lang.Number,"
                            + " java.lang.String). Check that the stream comes only from a trusted source, or give it a"
                            + " filter (ObjectInputStream.setObjectInputFilter or jdk.serialFilter).");
        });
    }

    @Test
    void aTrustManagerOfTheApplicationIsARowAndOneOfTheJdkIsNot() {
        start();
        inRequest(() -> Launcher.trust(new Object()));
        inRequest(() -> Launcher.trust(new Launcher.TrustAll()));

        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo(SideEffectsCatalog.TRUST_MANAGER);
            assertThat(row.target()).isEqualTo("sideeffectsapp.Launcher$TrustAll");
            assertThat(row.detail())
                    .startsWith("Application code initialized an SSLContext with a trust manager of its own"
                            + " (sideeffectsapp.Launcher$TrustAll) at `sideeffectsapp.Launcher#trust`.");
        });
    }

    @Test
    void theLimitationsSayWhatTheChecksSeeAndDoNot() {
        start();

        assertThat(service.sensor(SideEffectsCatalog.SECURITY_SINKS_ID, null, null)
                        .limitations())
                .contains(SideEffectsService.LIMITATION_SECURITY_CHECKS);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private List<SideEffectsRowDto> rows() {
        return service.sensor(SideEffectsCatalog.SECURITY_SINKS_ID, null, null).rows();
    }

    private void inRequest(Runnable work) {
        context.set(CorrelationContext.forRequest(REQUEST));
        try {
            work.run();
        } finally {
            context.set(CorrelationContext.NONE);
        }
    }

    private static ObjectInputStream stream() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(new ArrayList<>(List.of(1)));
            }
            return new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
    }

    private void start() {
        claim = AgentClaim.claim(
                AgentBridgeAccess.bind(AgentBridge.class),
                "shop",
                "shop-owner",
                "dev",
                List.of("sideeffectsapp"),
                new AgentSensorSettings(
                        List.of(AgentSensorSettings.SECURITY_SINKS),
                        List.of(),
                        List.of(),
                        null,
                        AgentSensorSettings.DEFAULT_RING_CAPACITY));
        claim.attach(new AgentHandoffs(context::get, null, null));
        SideEffects.enable(SideEffects.MASK_SECURITY_SINKS);
        SecuritySinks.groups(SecuritySinks.GROUPS, null);
        service = new SideEffectsService(
                AgentBridgeAccess.bind(AgentBridge.class),
                () -> claim,
                () -> null,
                id -> new JavaAgentService.SideEffectsCoverage(
                        SideEffectsSensorDto.RECORDING, null, List.of(), 0L, Map.of()),
                evidence,
                clock::get,
                "/home/someone");
        service.setRequestRoutes(ids -> {
            Map<String, String> named = new LinkedHashMap<>();
            for (String id : ids) {
                named.put(id, "GET /api/sample/hash");
            }
            return named;
        });
    }

    private static void resetBridge() {
        try {
            Method reset = AgentBridge.class.getDeclaredMethod("reset");
            reset.setAccessible(true);
            reset.invoke(null);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
