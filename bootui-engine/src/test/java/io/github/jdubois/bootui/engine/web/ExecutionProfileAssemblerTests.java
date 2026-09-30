package io.github.jdubois.bootui.engine.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.ExceptionOccurrenceDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.RequestProfileCacheAccessDto;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileExceptionDto;
import io.github.jdubois.bootui.core.dto.RequestProfileSectionDto;
import io.github.jdubois.bootui.core.dto.RequestProfileSecurityDto;
import io.github.jdubois.bootui.core.dto.RequestProfileTierDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.TraceDetailDto;
import io.github.jdubois.bootui.engine.cache.CacheActivityEvent;
import io.github.jdubois.bootui.engine.cache.CacheActivityOperation;
import io.github.jdubois.bootui.engine.web.ProfileCapabilities.ServingThread;
import io.github.jdubois.bootui.engine.web.ProfileCapabilities.ThreadMatch;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the shared execution-profile assembler every adapter serves {@code GET
 * /bootui/api/activity/request/{id}} through: the trace-id-only policy Spring WebFlux and Quarkus provide,
 * the full tiered policy Spring MVC provides, the REST client and cache sections, the cross-anchor
 * trace-uniqueness guard, the unique-candidate rule, bounds, and the tier report.
 */
class ExecutionProfileAssemblerTests {

    private static final long START = 1_000_000L;

    private final ExecutionProfileAssembler assembler = new ExecutionProfileAssembler();

    /** Spring WebFlux and Quarkus: trace id only. */
    @Nested
    class TraceIdOnly {

        private final ProfileCapabilities capabilities = ProfileCapabilities.traceIdOnly();

        @Test
        void unavailableWhenRequestNotFound() {
            RequestProfileDto profile = assembler.requestProfile("missing-1", evidence(List.of()), capabilities);

            assertThat(profile.available()).isFalse();
            assertThat(profile.unavailableReason()).isEqualTo("Request missing-1 is no longer in the buffer");
        }

        @Test
        void unavailableWhenRequestHasNoTraceId() {
            HttpExchangeDto request = request("req-1", "/orders", null, null, 1_000L, 50L);

            RequestProfileDto profile = assembler.requestProfile("req-1", evidence(List.of(request)), capabilities);

            assertThat(profile.available()).isFalse();
            assertThat(profile.unavailableReason())
                    .contains("No distributed trace id and no BootUI request id was captured");
            assertThat(profile.unavailableReason()).contains("OpenTelemetry");
        }

        @Test
        void correlatesSqlExceptionAndSecurityExactlyByTraceId() {
            HttpExchangeDto request = request("req-1", "/orders", "trace-a", "alice", 1_000L, 50L);
            ProfileEvidence evidence = new Evidence(request)
                    .sql(sql(1, "select 1", "trace-a", 10L, 1_010L), sql(2, "select 2", "trace-b", 5L, 1_020L))
                    .exceptions(exceptionDetail("g-1", "trace-a", 1_030L))
                    .security(
                            security("alice", "AUTHENTICATION_SUCCESS", "trace-a", 1_040L),
                            security("bob", "AUTHENTICATION_SUCCESS", "trace-b", 1_050L))
                    .build();

            RequestProfileDto profile = assembler.requestProfile("req-1", evidence, capabilities);

            assertThat(profile.available()).isTrue();
            assertThat(profile.unavailableReason()).isNull();
            assertThat(profile.request()).isEqualTo(request);
            assertThat(profile.sqlCorrelationApproximate()).isFalse();
            assertThat(profile.approximate()).isFalse();

            assertThat(profile.sql()).extracting(SqlTraceEntryDto::id).containsExactly(1L);
            assertThat(profile.exceptions()).hasSize(1);
            assertThat(profile.exceptions().get(0).exceptionClassName()).isEqualTo("java.lang.IllegalStateException");

            assertThat(profile.security()).hasSize(1);
            RequestProfileSecurityDto securityDto = profile.security().get(0);
            assertThat(securityDto.principal()).isEqualTo("alice");
            assertThat(securityDto.principalMatched()).isTrue();
            assertThat(securityDto.threadMatched()).isFalse();

            assertThat(profile.notes())
                    .anyMatch(note -> note.contains("reduced profile"))
                    .anyMatch(note -> note.contains("SQL is correlated exactly by trace id trace-a"))
                    .anyMatch(note -> note.contains("Exceptions are correlated exactly by trace id trace-a"))
                    .anyMatch(note -> note.contains("Security events are correlated exactly by trace id trace-a"));
            assertThat(section(profile, "SQL").tier()).isEqualTo("TRACE_ID");
            assertThat(section(profile, "EXCEPTION").tier()).isEqualTo("TRACE_ID");
            assertThat(section(profile, "SECURITY").tier()).isEqualTo("TRACE_ID");
        }

        @Test
        void keepsTheTraceWindowOfARequestOpen() {
            // A request's trace id claims work it caused even after the response completed.
            HttpExchangeDto request = request("req-1", "/orders", "trace-a", null, 1_000L, 50L);
            ProfileEvidence evidence = new Evidence(request)
                    .sql(sql(1, "select 1", "trace-a", 1L, 1_010L), sql(2, "select 2", "trace-a", 1L, 90_000L))
                    .build();

            RequestProfileDto profile = assembler.requestProfile("req-1", evidence, capabilities);

            assertThat(profile.sql()).extracting(SqlTraceEntryDto::id).containsExactly(1L, 2L);
        }

        @Test
        void skipsCorrelationWhenTheRequestsTraceIdIsAmbiguous() {
            HttpExchangeDto request = request("req-1", "/orders", "trace-a", null, 1_000L, 50L);
            HttpExchangeDto other = request("req-2", "/items", "trace-a", null, 2_000L, 50L);
            ProfileEvidence evidence = new Evidence(request, other)
                    .sql(sql(1, "select 1", "trace-a", 10L, 1_010L))
                    .restCalls(restCall(7, "trace-a", "worker-1", 1_020L))
                    .build();

            RequestProfileDto profile = assembler.requestProfile("req-1", evidence, capabilities);

            assertThat(profile.available()).isTrue();
            assertThat(profile.sql()).isEmpty();
            assertThat(profile.restCalls()).isEmpty();
            assertThat(profile.notes()).anyMatch(note -> note.contains("shared by more than one captured request"));
            assertThat(section(profile, "SQL").ambiguous()).isEqualTo(1);
            assertThat(section(profile, "REST_CLIENT").ambiguous()).isEqualTo(1);
            assertThat(profile.notes())
                    .anyMatch(note -> note.startsWith("1 SQL statement(s) could equally belong to another"))
                    .anyMatch(note -> note.startsWith("1 REST client call(s) could equally belong to another"));
        }

        @Test
        void correlatesRestClientCallsAndCacheAccessesByTraceId() {
            HttpExchangeDto request = request("req-1", "/orders", "trace-a", null, 1_000L, 500L);
            ProfileEvidence evidence = new Evidence(request)
                    .restCalls(
                            restCall(2, "trace-a", "reactor-http-nio-2", 1_200L),
                            restCall(1, "trace-a", "reactor-http-nio-3", 1_100L),
                            restCall(3, "trace-b", "reactor-http-nio-2", 1_150L))
                    .cache(
                            cache(11, "trace-a", "reactor-http-nio-2", 1_050L, CacheActivityOperation.MISS),
                            cache(12, null, "reactor-http-nio-2", 1_060L, CacheActivityOperation.PUT))
                    .build();

            RequestProfileDto profile = assembler.requestProfile("req-1", evidence, capabilities);

            assertThat(profile.restCalls())
                    .extracting(RestClientTraceEntryDto::id)
                    .containsExactly(1L, 2L);
            assertThat(profile.cacheAccesses())
                    .containsExactly(new RequestProfileCacheAccessDto(
                            1_050L, "cacheManager", "orders", "MISS", "a1b2c3d4e5f60718", "reactor-http-nio-2"));
            assertThat(section(profile, "REST_CLIENT"))
                    .isEqualTo(new RequestProfileSectionDto(
                            "REST_CLIENT", true, null, "TRACE_ID", List.of("TRACE_ID", "TRACE_ID"), 2, 0, 0));
            assertThat(section(profile, "CACHE"))
                    .isEqualTo(new RequestProfileSectionDto(
                            "CACHE", true, null, "TRACE_ID", List.of("TRACE_ID"), 1, 0, 0));
            assertThat(profile.timing().restCallCount()).isEqualTo(2);
            assertThat(profile.timing().restCallMs()).isEqualTo(2 * 40L);
            assertThat(profile.notes())
                    .contains("REST client calls are correlated exactly by trace id trace-a.")
                    .contains("Cache accesses are correlated exactly by trace id trace-a.");
        }

        @Test
        void aRequestWithOnlyARequestIdGetsAnExactProfileWithoutTracing() {
            HttpExchangeDto request = stamped(request("0123456789abcdef", "/orders", null, "alice", 1_000L, 50L));
            HttpExchangeDto twin = stamped(request("fedcba9876543210", "/orders", null, "alice", 1_000L, 50L));
            ProfileEvidence evidence = new Evidence(request, twin)
                    .sql(
                            withRequestId(sql(1, "select 1", null, 2L, 1_010L), "0123456789abcdef"),
                            withRequestId(sql(2, "select 2", null, 2L, 1_010L), "fedcba9876543210"),
                            sql(3, "select 3", null, 2L, 1_010L))
                    .security(new SecurityLogEventDto(
                            Instant.ofEpochMilli(1_020L).toString(),
                            "alice",
                            "AUTHENTICATION_SUCCESS",
                            List.of(),
                            null,
                            "0123456789abcdef"))
                    .restCalls(withRequestId(restCall(7, null, "worker-1", 1_030L), "0123456789abcdef"))
                    .cache(new CacheActivityEvent(
                            9L,
                            1_040L,
                            "cacheManager",
                            "orders",
                            CacheActivityOperation.HIT,
                            "h",
                            null,
                            "worker-1",
                            "fedcba9876543210"))
                    .build();

            RequestProfileDto profile = assembler.requestProfile("0123456789abcdef", evidence, capabilities);

            assertThat(profile.available()).isTrue();
            assertThat(profile.approximate()).isFalse();
            assertThat(profile.sql()).extracting(SqlTraceEntryDto::id).containsExactly(1L);
            assertThat(profile.security())
                    .singleElement()
                    .extracting(RequestProfileSecurityDto::principal)
                    .isEqualTo("alice");
            assertThat(profile.restCalls())
                    .extracting(RestClientTraceEntryDto::id)
                    .containsExactly(7L);
            assertThat(profile.cacheAccesses()).isEmpty();
            assertThat(section(profile, "SQL").tier()).isEqualTo("REQUEST_ID");
            assertThat(section(profile, "SECURITY").tier()).isEqualTo("REQUEST_ID");
            assertThat(section(profile, "REST_CLIENT").tier()).isEqualTo("REQUEST_ID");
            assertThat(profile.notes())
                    .anyMatch(note -> note.contains("reduced profile"))
                    .anyMatch(note -> note.startsWith("SQL statements carrying this request's BootUI request id"))
                    .anyMatch(note -> note.startsWith("REST client calls carrying this request's BootUI request id"));
        }

        @Test
        void attributesExceptionOccurrencesByRequestIdWithoutTracing() {
            HttpExchangeDto request = stamped(request("0123456789abcdef", "/orders", null, null, 1_000L, 50L));
            HttpExchangeDto twin = stamped(request("fedcba9876543210", "/orders", null, null, 1_000L, 50L));
            ProfileEvidence evidence = new Evidence(request, twin)
                    .exceptions(occurrences(
                            "java.lang.IllegalStateException",
                            new ExceptionOccurrenceDto(
                                    1_010L, "worker-1", "GET", "/orders", "h", "web", null, "0123456789abcdef"),
                            new ExceptionOccurrenceDto(
                                    1_020L, "worker-2", "GET", "/orders", "h", "web", null, "fedcba9876543210")))
                    .build();

            RequestProfileDto profile = assembler.requestProfile("0123456789abcdef", evidence, capabilities);

            assertThat(profile.exceptions())
                    .extracting(RequestProfileExceptionDto::timestamp)
                    .containsExactly(1_010L);
            assertThat(section(profile, "EXCEPTION").tier()).isEqualTo("REQUEST_ID");
            assertThat(profile.notes())
                    .anyMatch(
                            note -> note.startsWith("Exception occurrences carrying this request's BootUI request id"));
        }

        @Test
        void aRequestIdDecidesBeforeASharedTraceId() {
            HttpExchangeDto request = stamped(request("0123456789abcdef", "/orders", "trace-a", null, 1_000L, 50L));
            HttpExchangeDto other = stamped(request("fedcba9876543210", "/orders", "trace-a", null, 1_000L, 50L));
            ProfileEvidence evidence = new Evidence(request, other)
                    .sql(
                            withRequestId(sql(1, "select 1", "trace-a", 2L, 1_010L), "0123456789abcdef"),
                            withRequestId(sql(2, "select 2", "trace-a", 2L, 1_010L), "fedcba9876543210"),
                            withRequestId(sql(3, "select 3", "trace-a", 2L, 1_010L), "not-captured"))
                    .build();

            RequestProfileDto profile = assembler.requestProfile("0123456789abcdef", evidence, capabilities);

            assertThat(profile.sql())
                    .as("a statement carrying another request's id, or only the shared trace id, is not ours")
                    .extracting(SqlTraceEntryDto::id)
                    .containsExactly(1L);
            assertThat(section(profile, "SQL").ambiguous())
                    .as("the shared trace id leaves the uncaptured id's statement ambiguous")
                    .isEqualTo(1);
        }

        @Test
        void reportsServingThreadAndTimeWindowTiersUnavailable() {
            HttpExchangeDto request = request("req-1", "/orders", "trace-a", null, 1_000L, 50L);

            RequestProfileDto profile = assembler.requestProfile("req-1", evidence(List.of(request)), capabilities);

            assertThat(profile.correlationTiers())
                    .containsExactly(
                            new RequestProfileTierDto("REQUEST_ID", true, null),
                            new RequestProfileTierDto("TRACE_ID", true, null),
                            new RequestProfileTierDto("SERVING_THREAD", false, ProfileCapabilities.EVENT_LOOP_REASON),
                            new RequestProfileTierDto("TIME_WINDOW", false, ProfileCapabilities.EVENT_LOOP_REASON));
        }

        @Test
        void neverInfersThreadOrWindowMatchesItCannotProve() {
            // Same thread and window as the request, but no trace id: an event-loop thread proves nothing.
            HttpExchangeDto request = request("req-1", "/orders", "trace-a", null, 1_000L, 500L);
            ProfileEvidence evidence = new Evidence(request)
                    .sql(sqlOnThread(1, null, "worker-1", 1_010L))
                    .restCalls(restCall(1, null, "worker-1", 1_020L))
                    .cache(cache(1, null, "worker-1", 1_030L, CacheActivityOperation.HIT))
                    .exceptions(exceptionDetail("g-1", null, 1_040L))
                    .security(security(null, "AUTHENTICATION_SUCCESS", null, 1_050L))
                    .build();

            RequestProfileDto profile = assembler.requestProfile("req-1", evidence, capabilities);

            assertThat(profile.sql()).isEmpty();
            assertThat(profile.restCalls()).isEmpty();
            assertThat(profile.cacheAccesses()).isEmpty();
            assertThat(profile.exceptions()).isEmpty();
            assertThat(profile.security()).isEmpty();
            assertThat(profile.sections())
                    .allSatisfy(section -> assertThat(section.tier()).isNull());
        }

        @Test
        void groupsRepeatedSelectsAsPotentialNPlusOne() {
            HttpExchangeDto request = request("req-1", "/orders", "trace-a", null, 1_000L, 500L);
            ProfileEvidence evidence = new Evidence(request)
                    .sql(
                            sql(
                                    1,
                                    "select * from item where order_id = ?",
                                    "trace-a",
                                    5L,
                                    1_001L,
                                    "com.example.OrderService.loadItems(OrderService.java:42)"),
                            sql(
                                    2,
                                    "select * from item where order_id = ?",
                                    "trace-a",
                                    5L,
                                    1_002L,
                                    "com.example.OrderService.loadItems(OrderService.java:42)"),
                            sql(
                                    3,
                                    "select * from item where order_id = ?",
                                    "trace-a",
                                    5L,
                                    1_003L,
                                    "com.example.OrderRepository.findByOrderId(OrderRepository.java:17)"),
                            sql(4, "select * from item where order_id = ?", "trace-a", 5L, 1_004L, null),
                            sql(
                                    5,
                                    "select * from item where order_id = ?",
                                    "trace-a",
                                    5L,
                                    1_005L,
                                    "com.example.OrderService.loadItems(OrderService.java:42)"))
                    .build();

            RequestProfileDto profile = assembler.requestProfile("req-1", evidence, capabilities);

            assertThat(profile.sqlGroups()).hasSize(1);
            assertThat(profile.sqlGroups().get(0).executions()).isEqualTo(5L);
            assertThat(profile.sqlGroups().get(0).potentialNPlusOne()).isTrue();
            assertThat(profile.sqlGroups().get(0).callSites())
                    .containsExactly(
                            "com.example.OrderService.loadItems(OrderService.java:42)",
                            "com.example.OrderRepository.findByOrderId(OrderRepository.java:17)");

            assertThat(profile.timing().sqlCount()).isEqualTo(5);
            assertThat(profile.timing().sqlMs()).isEqualTo(25L);
            assertThat(profile.timing().totalMs()).isEqualTo(500L);
            assertThat(profile.timing().sqlPercent()).isEqualTo(5.0);
        }

        @Test
        void sumsSubMillisecondStatementsFromMicrosecondsNotRoundedMillis() {
            // Three 400 µs statements each round to 0 ms; the profile must still report their 1.2 ms total.
            HttpExchangeDto request = request("req-1", "/orders", "trace-a", null, 1_000L, 10L);
            ProfileEvidence evidence = new Evidence(request)
                    .sql(
                            sqlMicros(1, "select * from item where id = ?", "trace-a", 400L, 1_001L),
                            sqlMicros(2, "select * from item where id = ?", "trace-a", 400L, 1_002L),
                            sqlMicros(3, "select * from item where id = ?", "trace-a", 400L, 1_003L))
                    .build();

            RequestProfileDto profile = assembler.requestProfile("req-1", evidence, capabilities);

            assertThat(profile.timing().sqlCount()).isEqualTo(3);
            assertThat(profile.timing().sqlMs()).isEqualTo(1.2);
        }

        @Test
        void resolvesTraceOnlyWhenItsIdMatchesTheRequest() {
            HttpExchangeDto request = request("req-1", "/orders", "trace-a", null, 1_000L, 50L);
            TraceDetailDto matchingTrace = new TraceDetailDto("trace-a", List.of());
            TraceDetailDto mismatchedTrace = new TraceDetailDto("trace-other", List.of());

            RequestProfileDto matched = assembler.requestProfile(
                    "req-1",
                    new Evidence(request)
                            .traces(Map.of("trace-a", matchingTrace))
                            .build(),
                    capabilities);
            RequestProfileDto mismatched = assembler.requestProfile(
                    "req-1",
                    new Evidence(request)
                            .traces(Map.of("trace-a", mismatchedTrace))
                            .build(),
                    capabilities);

            assertThat(matched.trace()).isEqualTo(matchingTrace);
            assertThat(matched.notes()).anyMatch(note -> note.contains("Trace matched by id trace-a"));
            assertThat(mismatched.trace()).isNull();
        }

        @Test
        void reportsEachUnavailableSourceWithItsReason() {
            HttpExchangeDto request = request("req-1", "/orders", "trace-a", null, 1_000L, 50L);
            ProfileEvidence evidence = new ProfileEvidence(
                    List.of(request),
                    ProfileEvidence.Source.panelDisabled("SQL Trace"),
                    ProfileEvidence.Source.of(List.of()),
                    ProfileEvidence.Source.of(List.of()),
                    ProfileEvidence.Source.notCapturing("REST Client"),
                    ProfileEvidence.Source.unavailable("Cache access capture is not available on Quarkus."),
                    null);

            RequestProfileDto profile = assembler.requestProfile("req-1", evidence, capabilities);

            assertThat(profile.sections())
                    .containsExactly(
                            new RequestProfileSectionDto(
                                    "SQL", false, "The SQL Trace panel is disabled.", null, List.of(), 0, 0, 0),
                            new RequestProfileSectionDto("EXCEPTION", true, null, null, List.of(), 0, 0, 0),
                            new RequestProfileSectionDto("SECURITY", true, null, null, List.of(), 0, 0, 0),
                            new RequestProfileSectionDto(
                                    "REST_CLIENT",
                                    false,
                                    "REST Client is not capturing on this application.",
                                    null,
                                    List.of(),
                                    0,
                                    0,
                                    0),
                            new RequestProfileSectionDto(
                                    "CACHE",
                                    false,
                                    "Cache access capture is not available on Quarkus.",
                                    null,
                                    List.of(),
                                    0,
                                    0,
                                    0));
        }
    }

    /** Spring MVC: trace id, serving thread, then time window. */
    @Nested
    class ThreadPerRequest {

        private final List<ServingThread> registry = new ArrayList<>();
        private final List<SecurityCapture> securityCaptures = new ArrayList<>();
        private final ProfileCapabilities capabilities =
                ProfileCapabilities.threadPerRequest(this::resolve, this::classify);

        private ServingThread resolve(String method, String path, long start, long end) {
            ServingThread found = null;
            for (ServingThread candidate : registry) {
                if (candidate.startMillis() > end + 50 || candidate.endMillis() < start - 50) {
                    continue;
                }
                if (found != null) {
                    return null;
                }
                found = candidate;
            }
            return found;
        }

        private ThreadMatch classify(String servingThread, String type, long timestamp) {
            boolean foreign = false;
            for (SecurityCapture capture : securityCaptures) {
                if (!capture.type().equals(type) || Math.abs(capture.millis() - timestamp) > 2) {
                    continue;
                }
                if (capture.thread().equals(servingThread)) {
                    return ThreadMatch.OURS;
                }
                foreign = true;
            }
            return foreign ? ThreadMatch.FOREIGN : ThreadMatch.UNKNOWN;
        }

        @Test
        void profilesARequestWithoutATraceId() {
            HttpExchangeDto request = request("r1", "/a", null, null, START, 100L);

            RequestProfileDto profile = assembler.requestProfile("r1", evidence(List.of(request)), capabilities);

            assertThat(profile.available()).isTrue();
            assertThat(profile.notes()).noneMatch(note -> note.contains("reduced profile"));
            assertThat(profile.correlationTiers())
                    .allSatisfy(tier -> assertThat(tier.available()).isTrue());
        }

        @Test
        void correlatesSqlExactlyByServingThreadWhenNoTraceId() {
            registry.add(new ServingThread("exec-1", START, START + 100));
            HttpExchangeDto request = request("r1", "/a", null, null, START, 100L);
            ProfileEvidence evidence = new Evidence(request)
                    .sql(
                            sqlOnThread(1, null, "exec-1", START + 10),
                            sqlOnThread(2, null, "exec-2", START + 20),
                            sqlOnThread(3, null, "exec-1", START + 50))
                    .build();

            RequestProfileDto profile = assembler.requestProfile("r1", evidence, capabilities);

            assertThat(profile.sql()).extracting(SqlTraceEntryDto::id).containsExactly(1L, 3L);
            assertThat(profile.sqlCorrelationApproximate()).isFalse();
            assertThat(profile.approximate()).isFalse();
            assertThat(section(profile, "SQL").tier()).isEqualTo("SERVING_THREAD");
            assertThat(profile.notes()).anyMatch(note -> note.contains("serving thread"));
        }

        @Test
        void fallsBackToTheTimeWindowAndMarksTheProfileApproximate() {
            HttpExchangeDto request = request("r1", "/a", "trace-abc", null, START, 100L);
            ProfileEvidence evidence = new Evidence(request)
                    .sql(
                            sqlOnThread(1, "trace-other", "exec-9", START + 5),
                            sqlOnThread(2, null, "exec-9", START + 90),
                            sqlOnThread(3, null, "exec-9", START + 5_000))
                    .build();

            RequestProfileDto profile = assembler.requestProfile("r1", evidence, capabilities);

            assertThat(profile.sql()).extracting(SqlTraceEntryDto::id).containsExactly(1L, 2L);
            assertThat(profile.sqlCorrelationApproximate()).isTrue();
            assertThat(profile.approximate()).isTrue();
            assertThat(section(profile, "SQL").tier()).isEqualTo("TIME_WINDOW");
        }

        @Test
        void attributesAWindowStatementToNeitherOfTwoOverlappingRequests() {
            HttpExchangeDto first = request("r1", "/a", null, null, START, 100L);
            HttpExchangeDto second = request("r2", "/b", null, null, START + 50, 100L);
            ProfileEvidence evidence = new Evidence(first, second)
                    .sql(sqlOnThread(1, null, "exec-9", START + 10), sqlOnThread(2, null, "exec-9", START + 70))
                    .build();

            RequestProfileDto firstProfile = assembler.requestProfile("r1", evidence, capabilities);
            RequestProfileDto secondProfile = assembler.requestProfile("r2", evidence, capabilities);

            assertThat(firstProfile.sql()).extracting(SqlTraceEntryDto::id).containsExactly(1L);
            assertThat(secondProfile.sql()).isEmpty();
            assertThat(section(firstProfile, "SQL").ambiguous()).isEqualTo(1);
            assertThat(section(secondProfile, "SQL").ambiguous()).isEqualTo(1);
        }

        @Test
        void attributesSqlOnAReusedThreadToTheRequestWhoseExactWindowHoldsIt() {
            // exec-1 served r1, then r2 20 ms later: the slack windows overlap, the exact windows do not.
            HttpExchangeDto first = request("r1", "/a", null, null, START, 100L);
            HttpExchangeDto second = request("r2", "/b", null, null, START + 120, 100L);
            List<ServingThread> perRequest = List.of(
                    new ServingThread("exec-1", START, START + 100),
                    new ServingThread("exec-1", START + 120, START + 220));
            ProfileCapabilities reused = ProfileCapabilities.threadPerRequest(
                    (method, path, start, end) -> "/a".equals(path) ? perRequest.get(0) : perRequest.get(1), null);
            ProfileEvidence evidence = new Evidence(first, second)
                    .sql(sqlOnThread(1, null, "exec-1", START + 90), sqlOnThread(2, null, "exec-1", START + 130))
                    .build();

            assertThat(assembler.requestProfile("r1", evidence, reused).sql())
                    .extracting(SqlTraceEntryDto::id)
                    .containsExactly(1L);
            assertThat(assembler.requestProfile("r2", evidence, reused).sql())
                    .extracting(SqlTraceEntryDto::id)
                    .containsExactly(2L);
        }

        @Test
        void skipsOnlyTheTraceTierWhenTheTraceIdIsShared() {
            registry.add(new ServingThread("exec-1", START, START + 100));
            HttpExchangeDto first = request("r1", "/a", "trace-a", null, START, 100L);
            HttpExchangeDto second = request("r2", "/b", "trace-a", null, START + 5_000, 100L);
            ProfileCapabilities capabilities = ProfileCapabilities.threadPerRequest(
                    (method, path, start, end) -> "/a".equals(path) ? registry.get(0) : null, null);
            ProfileEvidence evidence = new Evidence(first, second)
                    .sql(
                            sqlOnThread(1, "trace-a", "exec-1", START + 10),
                            sqlOnThread(2, "trace-a", "exec-7", START + 5_010))
                    .build();

            RequestProfileDto profile = assembler.requestProfile("r1", evidence, capabilities);

            assertThat(profile.sql()).extracting(SqlTraceEntryDto::id).containsExactly(1L);
            assertThat(section(profile, "SQL").tier()).isEqualTo("SERVING_THREAD");
            assertThat(section(profile, "SQL").ambiguous()).isEqualTo(1);
            assertThat(profile.notes()).anyMatch(note -> note.contains("so trace-id correlation was skipped"));
        }

        @Test
        void correlatesRestClientCallsAndCacheAccessesByServingThreadButNeverByWindow() {
            registry.add(new ServingThread("exec-1", START, START + 100));
            HttpExchangeDto request = request("r1", "/a", null, null, START, 100L);
            ProfileEvidence evidence = new Evidence(request)
                    .restCalls(restCall(1, null, "exec-1", START + 10), restCall(2, null, "scheduling-1", START + 20))
                    .cache(
                            cache(1, null, "exec-1", START + 30, CacheActivityOperation.HIT),
                            cache(2, null, "scheduling-1", START + 40, CacheActivityOperation.MISS))
                    .build();

            RequestProfileDto profile = assembler.requestProfile("r1", evidence, capabilities);

            assertThat(profile.restCalls())
                    .extracting(RestClientTraceEntryDto::id)
                    .containsExactly(1L);
            assertThat(profile.cacheAccesses())
                    .extracting(RequestProfileCacheAccessDto::operation)
                    .containsExactly("HIT");
            assertThat(section(profile, "REST_CLIENT").tier()).isEqualTo("SERVING_THREAD");
            assertThat(section(profile, "CACHE").tier()).isEqualTo("SERVING_THREAD");
            assertThat(profile.approximate()).isFalse();
            assertThat(profile.notes())
                    .contains(
                            "REST client calls are correlated exactly by the request's serving thread within its window.")
                    .contains(
                            "Cache accesses are correlated exactly by the request's serving thread within its window.");
        }

        @Test
        void correlatesExceptionsByPathMethodAndWindow() {
            HttpExchangeDto request = request("r1", "/a", null, null, START, 100L);
            ProfileEvidence evidence = new Evidence(request)
                    .exceptions(
                            occurrences("ex-GET-/a", occurrence(START + 10, "t", "GET", "/a")),
                            occurrences("ex-POST-/a", occurrence(START + 10, "t", "POST", "/a")),
                            occurrences("ex-GET-/b", occurrence(START + 10, "t", "GET", "/b")))
                    .build();

            RequestProfileDto profile = assembler.requestProfile("r1", evidence, capabilities);

            assertThat(profile.exceptions())
                    .extracting(RequestProfileExceptionDto::exceptionClassName)
                    .containsExactly("ex-GET-/a");
            assertThat(section(profile, "EXCEPTION").tier()).isEqualTo("TIME_WINDOW");
            assertThat(profile.approximate()).isTrue();
            assertThat(profile.sqlCorrelationApproximate()).isFalse();
            assertThat(profile.notes()).contains("Exceptions are matched by request method, path and time window.");
        }

        @Test
        void correlatesExceptionsExactlyByServingThreadAcrossConcurrentIdenticalRequests() {
            registry.add(new ServingThread("exec-1", START, START + 100));
            HttpExchangeDto request = request("r1", "/a", null, null, START, 100L);
            ProfileEvidence evidence = new Evidence(request)
                    .exceptions(occurrences(
                            "ex",
                            occurrence(START + 10, "exec-1", "GET", "/a"),
                            occurrence(START + 20, "exec-2", "GET", "/a")))
                    .build();

            RequestProfileDto profile = assembler.requestProfile("r1", evidence, capabilities);

            assertThat(profile.exceptions())
                    .extracting(RequestProfileExceptionDto::thread)
                    .containsExactly("exec-1");
            assertThat(section(profile, "EXCEPTION").tier()).isEqualTo("SERVING_THREAD");
        }

        @Test
        void labelsExceptionsInsideTheRequestGateByTraceId() {
            HttpExchangeDto request = request("r1", "/orders", "trace-a", null, START, 100L);
            ProfileEvidence evidence = new Evidence(request)
                    .exceptions(exceptionDetail("g-1", "trace-a", START + 10))
                    .build();

            RequestProfileDto profile = assembler.requestProfile("r1", evidence, capabilities);

            assertThat(profile.exceptions()).hasSize(1);
            assertThat(section(profile, "EXCEPTION").tier()).isEqualTo("TRACE_ID");
            assertThat(profile.approximate()).isFalse();
        }

        @Test
        void keepsTheMethodPathAndWindowGateForExceptionsEvenWithAMatchingTraceId() {
            // Spring MVC's exception policy is unchanged: an occurrence outside the request's method, path,
            // and window is not this request's, whatever trace id it carries.
            HttpExchangeDto request = request("r1", "/a", "trace-a", null, START, 100L);
            ProfileEvidence evidence = new Evidence(request)
                    .exceptions(
                            exceptionDetail("g-1", "trace-a", START + 10),
                            exceptionDetail("g-2", "trace-a", START + 9_000))
                    .build();

            assertThat(assembler.requestProfile("r1", evidence, capabilities).exceptions())
                    .isEmpty();
            assertThat(assembler
                            .requestProfile("r1", evidence, ProfileCapabilities.traceIdOnly())
                            .exceptions())
                    .hasSize(2);
        }

        @Test
        void fallsBackToTheWindowForAnExceptionOnTheServingThreadOutsideItsRecordedWindow() {
            // The filter-recorded serving window can be narrower than the exchange window; an occurrence on
            // the same thread just outside it still matches the request's method, path, and window.
            registry.add(new ServingThread("exec-1", START + 30, START + 60));
            HttpExchangeDto request = request("r1", "/a", null, null, START, 150L);
            ProfileEvidence evidence = new Evidence(request)
                    .exceptions(occurrences("ex", occurrence(START + 140, "exec-1", "GET", "/a")))
                    .build();

            RequestProfileDto profile = assembler.requestProfile("r1", evidence, capabilities);

            assertThat(profile.exceptions()).hasSize(1);
            assertThat(section(profile, "EXCEPTION").tier()).isEqualTo("TIME_WINDOW");
        }

        @Test
        void attributesConcurrentIdenticalRequestExceptionsToNeither() {
            HttpExchangeDto first = request("r1", "/a", null, null, START, 100L);
            HttpExchangeDto second = request("r2", "/a", null, null, START + 10, 100L);
            ProfileEvidence evidence = new Evidence(first, second)
                    .exceptions(occurrences("ex", occurrence(START + 50, "exec-1", "GET", "/a")))
                    .build();

            RequestProfileDto profile = assembler.requestProfile("r1", evidence, capabilities);

            assertThat(profile.exceptions()).isEmpty();
            assertThat(section(profile, "EXCEPTION").ambiguous()).isEqualTo(1);
            assertThat(profile.notes())
                    .anyMatch(note -> note.startsWith("1 exception occurrence(s) could equally belong to another"));
        }

        @Test
        void labelsEachChildWithTheTierThatMatchedIt() {
            registry.add(new ServingThread("exec-1", START, START + 100));
            HttpExchangeDto request = request("r1", "/a", "trace-a", null, START, 100L);
            ProfileEvidence evidence = new Evidence(request)
                    .restCalls(restCall(1, null, "exec-1", START + 10), restCall(2, "trace-a", "exec-9", START + 20))
                    .build();

            RequestProfileSectionDto rest =
                    section(assembler.requestProfile("r1", evidence, capabilities), "REST_CLIENT");

            assertThat(rest.childTiers()).containsExactly("SERVING_THREAD", "TRACE_ID");
            assertThat(rest.tier()).isEqualTo("SERVING_THREAD");
        }

        @Test
        void pinsSecurityEventsToTheServingThreadAndDropsForeignOnes() {
            registry.add(new ServingThread("exec-1", START, START + 100));
            securityCaptures.add(new SecurityCapture(START + 5, "exec-1", "AUTHORIZATION_FAILURE"));
            securityCaptures.add(new SecurityCapture(START + 40, "exec-2", "AUTHORIZATION_FAILURE"));
            HttpExchangeDto request = request("r1", "/secure", null, "admin", START, 100L);
            ProfileEvidence evidence = new Evidence(request)
                    .security(
                            security("admin", "AUTHORIZATION_FAILURE", null, START + 5),
                            security("admin", "AUTHORIZATION_FAILURE", null, START + 40),
                            security("admin", "AUTHENTICATION_SUCCESS", null, START + 60),
                            security("bob", "AUTHENTICATION_SUCCESS", null, START + 70))
                    .build();

            RequestProfileDto profile = assembler.requestProfile("r1", evidence, capabilities);

            assertThat(profile.security())
                    .extracting(RequestProfileSecurityDto::timestamp)
                    .containsExactly(START + 5, START + 60);
            assertThat(profile.security().get(0).threadMatched()).isTrue();
            assertThat(profile.security().get(1).threadMatched()).isFalse();
            assertThat(section(profile, "SECURITY").tier()).isEqualTo("TIME_WINDOW");
            assertThat(profile.notes())
                    .anyMatch(note -> note.startsWith("Security events are matched exactly by the request's serving"))
                    .contains("Security events are matched by time window and the request principal admin.");
        }

        @Test
        void usesTheConfiguredNPlusOneThreshold() {
            HttpExchangeDto request = request("r1", "/a", null, null, START, 100L);
            ProfileEvidence evidence = new Evidence(request)
                    .sql(sqlOnThread(1, null, "t", START + 1), sqlOnThread(2, null, "t", START + 2))
                    .build();

            assertThat(new ExecutionProfileAssembler(2)
                            .requestProfile("r1", evidence, capabilities)
                            .sqlGroups()
                            .get(0)
                            .potentialNPlusOne())
                    .isTrue();
            assertThat(new ExecutionProfileAssembler(5)
                            .requestProfile("r1", evidence, capabilities)
                            .sqlGroups()
                            .get(0)
                            .potentialNPlusOne())
                    .isFalse();
        }
    }

    @Test
    void attachesEveryChildToAtMostOneRequestProfile() {
        List<ServingThread> threads = List.of(
                new ServingThread("exec-1", START, START + 100),
                new ServingThread("exec-2", START + 30, START + 130),
                new ServingThread("exec-1", START + 110, START + 200));
        HttpExchangeDto first = request("r1", "/a", "trace-1", "alice", START, 100L);
        HttpExchangeDto second = request("r2", "/b", "trace-2", "alice", START + 30, 100L);
        HttpExchangeDto third = request("r3", "/c", "trace-2", null, START + 110, 90L);
        List<HttpExchangeDto> requests = List.of(first, second, third);
        ProfileCapabilities capabilities = ProfileCapabilities.threadPerRequest(
                (method, path, start, end) -> threads.get(path.charAt(1) - 'a'), null);
        List<SqlTraceEntryDto> sql = new ArrayList<>();
        List<RestClientTraceEntryDto> rest = new ArrayList<>();
        List<CacheActivityEvent> cache = new ArrayList<>();
        List<ExceptionOccurrenceDto> occurrences = new ArrayList<>();
        List<SecurityLogEventDto> security = new ArrayList<>();
        for (int i = 0; i < 220; i += 7) {
            String trace = i % 3 == 0 ? "trace-1" : i % 3 == 1 ? "trace-2" : null;
            String thread = i % 2 == 0 ? "exec-1" : "exec-2";
            String path = "/" + (char) ('a' + (i % 3));
            sql.add(sqlOnThread(i, trace, thread, START + i));
            rest.add(restCall(i, trace, thread, START + i));
            cache.add(cache(i, trace, thread, START + i, CacheActivityOperation.HIT));
            occurrences.add(new ExceptionOccurrenceDto(START + i, thread, "GET", path, "h", "web", trace));
            security.add(security(i % 2 == 0 ? "alice" : null, "EVENT-" + i, trace, START + i));
        }
        ProfileEvidence evidence = new ProfileEvidence(
                requests,
                ProfileEvidence.Source.of(sql),
                ProfileEvidence.Source.of(
                        List.of(occurrences("ex", occurrences.toArray(ExceptionOccurrenceDto[]::new)))),
                ProfileEvidence.Source.of(security),
                ProfileEvidence.Source.of(rest),
                ProfileEvidence.Source.of(cache),
                null);

        List<Long> claimedSql = new ArrayList<>();
        List<Long> claimedRest = new ArrayList<>();
        List<Long> claimedCache = new ArrayList<>();
        List<Long> claimedExceptions = new ArrayList<>();
        List<Long> claimedSecurity = new ArrayList<>();
        for (HttpExchangeDto request : requests) {
            RequestProfileDto profile = assembler.requestProfile(request.id(), evidence, capabilities);
            profile.sql().forEach(entry -> claimedSql.add(entry.id()));
            profile.restCalls().forEach(entry -> claimedRest.add(entry.id()));
            profile.cacheAccesses().forEach(access -> claimedCache.add(access.timestamp()));
            profile.exceptions().forEach(exception -> claimedExceptions.add(exception.timestamp()));
            profile.security().forEach(event -> claimedSecurity.add(event.timestamp()));
        }

        assertThat(claimedSql).doesNotHaveDuplicates().isNotEmpty();
        assertThat(claimedRest).doesNotHaveDuplicates().isNotEmpty();
        assertThat(claimedCache).doesNotHaveDuplicates().isNotEmpty();
        assertThat(claimedExceptions).doesNotHaveDuplicates().isNotEmpty();
        assertThat(claimedSecurity).doesNotHaveDuplicates().isNotEmpty();
    }

    @Test
    void boundsEverySectionButGroupsAndTimesEveryCorrelatedChild() {
        ExecutionProfileAssembler bounded = new ExecutionProfileAssembler(5, 2);
        HttpExchangeDto request = request("req-1", "/orders", "trace-a", null, 1_000L, 500L);
        ProfileEvidence evidence = new Evidence(request)
                .sql(
                        sql(1, "select 1", "trace-a", 1L, 1_001L),
                        sql(2, "select 1", "trace-a", 1L, 1_002L),
                        sql(3, "select 1", "trace-a", 1L, 1_003L),
                        sql(4, "select 1", "trace-a", 1L, 1_004L),
                        sql(5, "select 1", "trace-a", 1L, 1_005L))
                .restCalls(
                        restCall(1, "trace-a", "t", 1_010L),
                        restCall(2, "trace-a", "t", 1_020L),
                        restCall(3, "trace-a", "t", 1_030L))
                .build();

        RequestProfileDto profile = bounded.requestProfile("req-1", evidence, ProfileCapabilities.traceIdOnly());

        assertThat(profile.sql()).extracting(SqlTraceEntryDto::id).containsExactly(1L, 2L);
        assertThat(section(profile, "SQL").total()).isEqualTo(5);
        assertThat(section(profile, "SQL").truncated()).isEqualTo(3);
        assertThat(profile.sqlGroups().get(0).executions()).isEqualTo(5L);
        assertThat(profile.sqlGroups().get(0).potentialNPlusOne()).isTrue();
        assertThat(profile.timing().sqlCount()).isEqualTo(5);
        assertThat(profile.restCalls()).hasSize(2);
        assertThat(section(profile, "REST_CLIENT").truncated()).isEqualTo(1);
        assertThat(profile.timing().restCallCount()).isEqualTo(3);
    }

    @Test
    void boundsTheSqlGroupsItReturnsAndSaysSo() {
        ExecutionProfileAssembler bounded = new ExecutionProfileAssembler(5, 2);
        HttpExchangeDto request = request("req-1", "/orders", "trace-a", null, 1_000L, 500L);
        ProfileEvidence evidence = new Evidence(request)
                .sql(
                        sql(1, "select 1", "trace-a", 1L, 1_001L),
                        sql(2, "select 2", "trace-a", 1L, 1_002L),
                        sql(3, "select 3", "trace-a", 1L, 1_003L))
                .build();

        RequestProfileDto profile = bounded.requestProfile("req-1", evidence, ProfileCapabilities.traceIdOnly());

        assertThat(profile.sqlGroups()).hasSize(2);
        assertThat(profile.timing().sqlCount()).isEqualTo(3);
        assertThat(profile.notes()).contains("Showing the 2 most repeated of 3 distinct SQL statements.");
    }

    @Test
    void correlatesTracedEvidenceIdenticallyOnEveryAdapter() {
        HttpExchangeDto request = request("req-1", "/orders", "trace-a", "alice", 1_000L, 500L);
        ProfileEvidence evidence = new Evidence(request, request("req-2", "/items", "trace-b", null, 1_100L, 20L))
                .sql(sql(1, "select 1", "trace-a", 1L, 1_010L), sql(2, "select 2", "trace-b", 1L, 1_110L))
                .restCalls(restCall(1, "trace-a", "t-1", 1_020L), restCall(2, "trace-b", "t-2", 1_120L))
                .cache(cache(1, "trace-a", "t-1", 1_030L, CacheActivityOperation.HIT))
                .build();
        ProfileCapabilities threadPerRequest =
                ProfileCapabilities.threadPerRequest((method, path, start, end) -> null, null);

        RequestProfileDto traceIdOnly = assembler.requestProfile("req-1", evidence, ProfileCapabilities.traceIdOnly());
        RequestProfileDto everyTier = assembler.requestProfile("req-1", evidence, threadPerRequest);

        assertThat(everyTier.sql()).isEqualTo(traceIdOnly.sql());
        assertThat(everyTier.restCalls()).isEqualTo(traceIdOnly.restCalls());
        assertThat(everyTier.cacheAccesses()).isEqualTo(traceIdOnly.cacheAccesses());
        assertThat(everyTier.sections()).isEqualTo(traceIdOnly.sections());
        assertThat(everyTier.timing()).isEqualTo(traceIdOnly.timing());
        assertThat(everyTier.approximate()).isEqualTo(traceIdOnly.approximate()).isFalse();
    }

    @Test
    void keepsTheOriginalProfileShapeConstructible() {
        RequestProfileDto profile = new RequestProfileDto(
                true, null, null, List.of(), List.of(), true, List.of(), List.of(), null, null, List.of());

        assertThat(profile.restCalls()).isEmpty();
        assertThat(profile.cacheAccesses()).isEmpty();
        assertThat(profile.sections()).isEmpty();
        assertThat(profile.correlationTiers()).isEmpty();
        assertThat(profile.approximate()).isTrue();
    }

    // --- fixtures ---

    private record SecurityCapture(long millis, String thread, String type) {}

    private static final class Evidence {

        private final List<HttpExchangeDto> requests;
        private List<SqlTraceEntryDto> sql = List.of();
        private List<ExceptionDetailDto> exceptions = List.of();
        private List<SecurityLogEventDto> security = List.of();
        private List<RestClientTraceEntryDto> restCalls = List.of();
        private List<CacheActivityEvent> cache = List.of();
        private Map<String, TraceDetailDto> traces = Map.of();

        Evidence(HttpExchangeDto... requests) {
            this.requests = List.of(requests);
        }

        Evidence sql(SqlTraceEntryDto... entries) {
            sql = List.of(entries);
            return this;
        }

        Evidence exceptions(ExceptionDetailDto... details) {
            exceptions = List.of(details);
            return this;
        }

        Evidence security(SecurityLogEventDto... events) {
            security = List.of(events);
            return this;
        }

        Evidence restCalls(RestClientTraceEntryDto... calls) {
            restCalls = List.of(calls);
            return this;
        }

        Evidence cache(CacheActivityEvent... events) {
            cache = List.of(events);
            return this;
        }

        Evidence traces(Map<String, TraceDetailDto> byId) {
            traces = byId;
            return this;
        }

        ProfileEvidence build() {
            return new ProfileEvidence(
                    requests,
                    ProfileEvidence.Source.of(sql),
                    ProfileEvidence.Source.of(exceptions),
                    ProfileEvidence.Source.of(security),
                    ProfileEvidence.Source.of(restCalls),
                    ProfileEvidence.Source.of(cache),
                    traces::get);
        }
    }

    private static ProfileEvidence evidence(List<HttpExchangeDto> requests) {
        return new Evidence(requests.toArray(HttpExchangeDto[]::new)).build();
    }

    private static RequestProfileSectionDto section(RequestProfileDto profile, String type) {
        return profile.sections().stream()
                .filter(section -> type.equals(section.type()))
                .findFirst()
                .orElseThrow();
    }

    private static HttpExchangeDto request(
            String id, String path, String traceId, String principal, long epochMillis, long durationMs) {
        return new HttpExchangeDto(
                id,
                Instant.ofEpochMilli(epochMillis),
                "GET",
                path,
                null,
                "http://localhost:8080" + path,
                200,
                "2xx",
                durationMs,
                34L,
                "127.0.0.1",
                principal,
                null,
                traceId,
                List.of(),
                List.of());
    }

    /** The exchange stamped with BootUI's request id, which is then its id. */
    private static HttpExchangeDto stamped(HttpExchangeDto base) {
        return new HttpExchangeDto(
                base.id(),
                base.timestamp(),
                base.method(),
                base.path(),
                base.query(),
                base.uri(),
                base.status(),
                base.statusFamily(),
                base.durationMs(),
                base.responseSizeBytes(),
                base.remoteAddress(),
                base.principal(),
                base.sessionId(),
                base.traceId(),
                base.requestHeaders(),
                base.responseHeaders(),
                null,
                null,
                base.id());
    }

    private static SqlTraceEntryDto withRequestId(SqlTraceEntryDto entry, String requestId) {
        return new SqlTraceEntryDto(
                entry.id(),
                entry.timestamp(),
                entry.sql(),
                entry.statementType(),
                entry.category(),
                entry.durationMicros(),
                entry.durationMillis(),
                entry.success(),
                entry.errorMessage(),
                entry.affectedRows(),
                entry.batchSize(),
                entry.connectionId(),
                entry.thread(),
                entry.slow(),
                entry.parameters(),
                entry.traceId(),
                entry.callSite(),
                requestId);
    }

    private static RestClientTraceEntryDto withRequestId(RestClientTraceEntryDto call, String requestId) {
        return new RestClientTraceEntryDto(
                call.id(),
                call.timestamp(),
                call.method(),
                call.uri(),
                call.host(),
                call.path(),
                call.status(),
                call.durationMillis(),
                call.success(),
                call.errorMessage(),
                call.slow(),
                call.clientType(),
                call.requestHeaders(),
                call.traceId(),
                call.thread(),
                call.callSite(),
                requestId);
    }

    private static SqlTraceEntryDto sql(long id, String sql, String traceId, long durationMillis, long timestamp) {
        return sql(id, sql, traceId, durationMillis, timestamp, null);
    }

    private static SqlTraceEntryDto sql(
            long id, String sql, String traceId, long durationMillis, long timestamp, String callSite) {
        return sqlEntry(id, sql, traceId, durationMillis * 1_000L, timestamp, callSite, "worker-1");
    }

    private static SqlTraceEntryDto sqlMicros(
            long id, String sql, String traceId, long durationMicros, long timestamp) {
        return sqlEntry(id, sql, traceId, durationMicros, timestamp, null, "worker-1");
    }

    private static SqlTraceEntryDto sqlOnThread(long id, String traceId, String thread, long timestamp) {
        return sqlEntry(id, "select * from t", traceId, 3_000L, timestamp, null, thread);
    }

    private static SqlTraceEntryDto sqlEntry(
            long id, String sql, String traceId, long durationMicros, long timestamp, String callSite, String thread) {
        return new SqlTraceEntryDto(
                id,
                timestamp,
                sql,
                "PREPARED",
                "SELECT",
                durationMicros,
                Math.round(durationMicros / 1_000.0),
                true,
                null,
                null,
                0,
                "c1",
                thread,
                false,
                List.of(),
                traceId,
                callSite);
    }

    private static RestClientTraceEntryDto restCall(long id, String traceId, String thread, long timestamp) {
        return new RestClientTraceEntryDto(
                id,
                timestamp,
                "GET",
                "https://inventory.example/items?token=******",
                "inventory.example",
                "/items",
                200,
                40L,
                true,
                null,
                false,
                "WebClient",
                Map.of(),
                traceId,
                thread,
                null);
    }

    private static CacheActivityEvent cache(
            long seq, String traceId, String thread, long timestamp, CacheActivityOperation operation) {
        return new CacheActivityEvent(
                seq, timestamp, "cacheManager", "orders", operation, "a1b2c3d4e5f60718", traceId, thread);
    }

    private static SecurityLogEventDto security(String principal, String type, String traceId, long epochMillis) {
        return new SecurityLogEventDto(
                Instant.ofEpochMilli(epochMillis).toString(), principal, type, List.of(), traceId);
    }

    private static ExceptionOccurrenceDto occurrence(long timestamp, String thread, String method, String path) {
        return new ExceptionOccurrenceDto(timestamp, thread, method, path, "h", "web", null);
    }

    private static ExceptionDetailDto occurrences(String exceptionClassName, ExceptionOccurrenceDto... occurrences) {
        ExceptionOccurrenceDto last = occurrences[occurrences.length - 1];
        ExceptionGroupDto group = new ExceptionGroupDto(
                exceptionClassName,
                exceptionClassName,
                "boom",
                occurrences.length,
                occurrences[0].timestamp(),
                last.timestamp(),
                "Foo.java:1",
                true,
                last.thread(),
                last.requestMethod(),
                last.requestPath(),
                "h",
                "web",
                null,
                "OPEN",
                0,
                null);
        return new ExceptionDetailDto(group, List.of(), List.of(), List.of(occurrences));
    }

    private static ExceptionDetailDto exceptionDetail(String id, String traceId, long timestamp) {
        ExceptionGroupDto group = new ExceptionGroupDto(
                id,
                "java.lang.IllegalStateException",
                "boom",
                1,
                timestamp,
                timestamp,
                "Foo.java:1",
                true,
                "worker-1",
                "GET",
                "/orders",
                "Handler#x",
                "web",
                traceId,
                "OPEN",
                0,
                null);
        ExceptionOccurrenceDto occurrence =
                new ExceptionOccurrenceDto(timestamp, "worker-1", "GET", "/orders", "Handler#x", "web", traceId);
        return new ExceptionDetailDto(group, List.of(), List.of(), List.of(occurrence));
    }
}
