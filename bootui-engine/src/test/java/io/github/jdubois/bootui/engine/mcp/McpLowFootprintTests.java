package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.jdubois.bootui.core.dto.LowFootprintStatus;
import io.github.jdubois.bootui.core.dto.SuspendedComponentDto;
import io.github.jdubois.bootui.engine.memory.LowFootprintService;
import io.github.jdubois.bootui.spi.IdleReclaimable;
import io.github.jdubois.bootui.spi.LowFootprintSuspendable;
import io.github.jdubois.bootui.spi.MemoryOffloadable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

class McpLowFootprintTests {

    private static final McpArguments ARGS = new McpArguments(null, 10, "id");

    @Test
    void mappedPanelReadsAndControlsKeepTheirPayloadAndIdentifyTheActualPausedSource() {
        for (var entry : McpToolCatalog.entries()) {
            if (!McpLowFootprint.affected(entry.name()) || entry.name().startsWith("resume_")) {
                continue;
            }
            var service = service(
                    true,
                    McpLowFootprint.classifiedComponents().stream()
                            .map(id -> new SuspendedComponentDto(id, id, true, null))
                            .toList());
            Map<String, Object> original = Map.of("entries", List.of(), "page", Map.of("hasMore", false));
            Object response = McpLowFootprint.decorate(tool(entry.name(), original), service)
                    .invoke(ARGS);
            assertThat(response).as(entry.name()).isInstanceOf(McpLowFootprint.Result.class);
            var result = (McpLowFootprint.Result) response;
            assertThat(result.payload()).isSameAs(original);
            assertThat(result.lowFootprint())
                    .containsEntry("active", true)
                    .containsEntry("paused", true)
                    .containsEntry("sinceEpochMillis", 42L);
            assertThat((List<?>) result.lowFootprint().get("components")).isNotEmpty();
            assertThat(result.lowFootprint().get("hint").toString()).contains("disable_low_footprint");
        }
    }

    @Test
    void inactiveModeAndUnaffectedJvmConfigurationAndAgentReadsAreUntouched() {
        Map<String, Object> original = Map.of("enabled", true);
        for (String name : List.of("get_exceptions", "get_sql_traces", "get_overview")) {
            assertThat(McpLowFootprint.decorate(tool(name, original), service(false, List.of()))
                            .invoke(ARGS))
                    .isSameAs(original);
        }
        for (String name : List.of(
                "get_health",
                "get_config",
                "get_live_memory",
                "get_jvm_tuning",
                "memory_scan",
                "security_scan",
                "get_agent_status",
                "get_method_probe")) {
            assertThat(McpLowFootprint.decorate(
                                    tool(name, original),
                                    service(
                                            true,
                                            List.of(new SuspendedComponentDto("runtime-journal", "", true, null))))
                            .invoke(ARGS))
                    .as(name)
                    .isSameAs(original);
        }
    }

    @Test
    void absentComponentsAreNotInventedAndFailedSuspensionsAreNotCalledPaused() {
        Map<String, Object> original = Map.of("groups", List.of());
        assertThat(McpLowFootprint.decorate(tool("get_exceptions", original), service(true, List.of()))
                        .invoke(ARGS))
                .isSameAs(original);
        var failed = new SuspendedComponentDto("exceptions", "Exceptions", false, "could not pause");
        var result = (McpLowFootprint.Result)
                McpLowFootprint.decorate(tool("get_exceptions", original), service(true, List.of(failed)))
                        .invoke(ARGS);
        assertThat(result.lowFootprint()).containsEntry("paused", false);
        assertThat(result.lowFootprint().get("warnings").toString()).contains("could not pause");
        assertThat(result.lowFootprint().get("hint").toString()).doesNotContain("not a quiet application");
    }

    @Test
    void aConcurrentDisableCannotTurnAnInactiveSnapshotIntoAnActiveOverviewNotice() {
        var service = service(true, List.of());
        when(service.status()).thenReturn(new LowFootprintStatus(false, null, false, List.of(), List.of(), null));
        Map<String, Object> payload = Map.of("applicationName", "test");
        assertThat(McpLowFootprint.decorate(tool("get_overview", payload), service)
                        .invoke(ARGS))
                .isSameAs(payload);
    }

    @Test
    void enablingDuringAReadMarksItsPotentiallyIncompleteEvidence() {
        var service = service(true, List.of(new SuspendedComponentDto("exceptions", "Exceptions", true, null)));
        when(service.active()).thenReturn(false, true);
        var result = (McpLowFootprint.Result) McpLowFootprint.decorate(
                        tool("get_exceptions", Map.of("groups", List.of())), service)
                .invoke(ARGS);
        assertThat(result.lowFootprint()).containsEntry("active", true).containsEntry("paused", true);
    }

    @Test
    void mixedAgentEvidenceKeepsTheInstrumentationWarningAndCanonicalDetailErrors() {
        var service = service(true, List.of(new SuspendedComponentDto("runtime-journal", "Journal", true, null)));
        when(service.status())
                .thenReturn(new LowFootprintStatus(
                        true,
                        42L,
                        true,
                        List.of(new SuspendedComponentDto("runtime-journal", "Journal", true, null)),
                        List.of(LowFootprintService.AGENT_WARNING),
                        null));
        var result = (McpLowFootprint.Result)
                McpLowFootprint.decorate(tool("get_side_effects", Map.of("agentAttached", true)), service)
                        .invoke(ARGS);
        assertThat(result.lowFootprint().get("warnings").toString()).contains("keeps instrumenting");
        assertThat(result.lowFootprint().get("hint").toString()).contains("Native statistics", "Java-agent");
        var missing = new McpTool("get_exception_detail", "", McpToolSchema.ID, "exceptions", false, args -> {
            throw new McpToolClientException(404, "Exception no longer retained");
        });
        assertThatThrownBy(() -> McpLowFootprint.decorate(missing, service).invoke(ARGS))
                .isInstanceOf(McpToolClientException.class)
                .hasMessage("Exception no longer retained");
    }

    @Test
    void resumesAndNewProbesAreRefusedBeforeTheirHandlerButRunAfterTheHoldIsReleased() {
        for (String name : List.of(
                "resume_sql_trace_recording",
                "resume_transaction_recording",
                "resume_rest_client_recording",
                "start_method_probe")) {
            AtomicInteger calls = new AtomicInteger();
            var original = tool(name, null);
            var counting = new McpTool(
                    original.name(),
                    original.description(),
                    original.schema(),
                    original.panelId(),
                    original.action(),
                    args -> calls.incrementAndGet());
            var service = service(true, List.of());
            McpTool decorated = McpLowFootprint.decorate(counting, service);
            assertThatThrownBy(() -> decorated.invoke(ARGS))
                    .isInstanceOfSatisfying(McpToolClientException.class, error -> {
                        assertThat(error.status()).isEqualTo(409);
                        assertThat(error.getMessage()).contains(name, "disable_low_footprint");
                    });
            assertThat(calls).hasValue(0);
            when(service.active()).thenReturn(false);
            assertThat(decorated.invoke(ARGS)).isEqualTo(1);
        }
    }

    @Test
    void controlsUseTheExistingServiceAndNoneSchemaOnEveryStack() {
        var status = new LowFootprintStatus(false, null, false, List.of(), List.of(), null);
        var service = mock(LowFootprintService.class);
        when(service.status()).thenReturn(status);
        when(service.enable()).thenReturn(status);
        when(service.disable()).thenReturn(status);
        for (var stack : McpToolCatalog.Stack.values()) {
            List<McpTool> tools = McpLowFootprint.tools(service, stack);
            assertThat(tools)
                    .hasSize(3)
                    .allSatisfy(tool -> assertThat(tool.schema()).isEqualTo(McpToolSchema.NONE));
            assertThat(tools.get(0).invoke(ARGS)).isSameAs(status);
            assertThat(tools.get(1).invoke(ARGS))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsEntry("action", "enabled")
                    .containsEntry("active", false);
            assertThat(tools.get(2).invoke(ARGS))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsEntry("action", "disabled");
        }
    }

    @Test
    void globalHoldDoesNotChangeAnIndividuallyPausedRecordersPreference() {
        var recorder = new io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder(
                true, false, false, false, 10, 0L, 100, 64, 3);
        var service = new LowFootprintService(
                () -> List.of(recorder), mock(io.github.jdubois.bootui.engine.memory.MemoryOffloadService.class));
        var controls = McpLowFootprint.tools(service, McpToolCatalog.Stack.SPRING_MVC);
        controls.get(1).invoke(ARGS);
        controls.get(2).invoke(ARGS);
        assertThat(recorder.isRecording()).isFalse();
    }

    @Test
    void everyConcreteEngineSuspendableHasAnExplicitComponentClassification() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.jdubois.bootui.engine");
        for (var type : classes) {
            if (type.isInterface()
                    || type.getModifiers().contains(JavaModifier.ABSTRACT)
                    || (!type.isAssignableTo(LowFootprintSuspendable.class)
                            && !type.isAssignableTo(IdleReclaimable.class))
                    || type.getName().endsWith("LowFootprintService$IdleComponent")) {
                continue;
            }
            Object instance = mock(type.reflect(), Answers.CALLS_REAL_METHODS);
            String id = instance instanceof LowFootprintSuspendable part
                    ? part.suspendId()
                    : ((MemoryOffloadable) instance).offloadId();
            assertThat(McpLowFootprint.classifiedComponents())
                    .as(type.getName())
                    .contains(id);
        }
    }

    private static LowFootprintService service(boolean active, List<SuspendedComponentDto> components) {
        var service = mock(LowFootprintService.class);
        when(service.active()).thenReturn(active);
        when(service.status())
                .thenReturn(new LowFootprintStatus(active, active ? 42L : null, false, components, List.of(), null));
        return service;
    }

    private static McpTool tool(String name, Object payload) {
        var entry = McpToolCatalog.byName(name).orElseThrow();
        return new McpTool(name, name, entry.schema(), entry.panelId(), entry.action(), args -> payload);
    }
}
