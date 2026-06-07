package io.github.jdubois.bootui.autoconfigure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.web.MemoryCalculator.JdkVersion;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;

/**
 * Controller-level tests for {@link LiveMemoryController}.
 *
 * <p>Uses the package-private {@code MemoryReportProvider(MemoryCalculator)}
 * constructor to inject a {@link MemoryCalculator} with a pinned JDK version
 * so that JVM-option assertions are reproducible regardless of the host JDK.
 * Live {@link java.lang.management.ManagementFactory} data is used for heap,
 * non-heap and pool readings — these will always be non-negative in a running
 * JVM, which is all the tests need to assert about them.</p>
 *
 * <p>Does not duplicate the formula assertions already covered by
 * {@link MemoryCalculatorTests}.</p>
 */
class LiveMemoryControllerTests {

    private static final JdkVersion JDK_25 = () -> 25;

    @Test
    void memoryReturnsExpectedTopLevelShape() throws Exception {
        MockMvc mvc = memorySetup(new MemoryReportProvider(new MemoryCalculator(JDK_25)))
                .build();

        mvc.perform(get("/bootui/api/live-memory").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heap").isMap())
                .andExpect(jsonPath("$.heap.name").value("Heap"))
                .andExpect(jsonPath("$.nonHeap").isMap())
                .andExpect(jsonPath("$.nonHeap.name").value("Non-Heap"))
                .andExpect(jsonPath("$.pools").isArray())
                .andExpect(jsonPath("$.jvmInputArguments").isArray())
                .andExpect(jsonPath("$.suggestedJvmOptions").isString())
                .andExpect(jsonPath("$.calculation").isMap())
                .andExpect(jsonPath("$.calculation.virtualThreadsEnabled").value(false))
                .andExpect(jsonPath("$.kubernetes").isMap());
    }

    @Test
    void jvmTuningAliasReturnsMemoryReport() throws Exception {
        MockMvc mvc = memorySetup(new MemoryReportProvider(new MemoryCalculator(JDK_25)))
                .build();

        mvc.perform(get("/bootui/api/jvm-tuning").param("totalMemoryMb", "512").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heap.name").value("Heap"))
                .andExpect(jsonPath("$.calculation.totalMemoryBytes").value(512L * 1024L * 1024L))
                .andExpect(jsonPath("$.kubernetes").isMap());
    }

    @Test
    void memoryPoolDtoHasRequiredFields() throws Exception {
        MockMvc mvc = memorySetup(new MemoryReportProvider(new MemoryCalculator(JDK_25)))
                .build();

        mvc.perform(get("/bootui/api/live-memory").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heap.usedBytes").isNumber())
                .andExpect(jsonPath("$.heap.committedBytes").isNumber())
                .andExpect(jsonPath("$.heap.maxBytes").isNumber())
                .andExpect(jsonPath("$.heap.usedPercent").isNumber())
                .andExpect(jsonPath("$.nonHeap.usedBytes").isNumber());
    }

    @Test
    void memoryHonorsTotalMemoryMbOverrideParam() throws Exception {
        MockMvc mvc = memorySetup(new MemoryReportProvider(new MemoryCalculator(JDK_25)))
                .build();

        // With 512 MB total, calculation.totalMemoryBytes must reflect exactly 512 * 1024 * 1024
        long expectedBytes = 512L * 1024L * 1024L;
        mvc.perform(get("/bootui/api/live-memory").param("totalMemoryMb", "512").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculation.totalMemoryBytes").value(expectedBytes));
    }

    @Test
    void memoryHonorsThreadCountOverrideParam() throws Exception {
        MockMvc mvc = memorySetup(new MemoryReportProvider(new MemoryCalculator(JDK_25)))
                .build();

        mvc.perform(get("/bootui/api/live-memory")
                        .param("totalMemoryMb", "1024")
                        .param("threadCount", "50")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // 50 < MIN_THREAD_COUNT (1) floor is fine, but also < DEFAULT_THREAD_COUNT_FLOOR (250)
                // The clamp keeps it at max(1, min(10000, 50)) = 50
                .andExpect(jsonPath("$.calculation.threadCount").value(50));
    }

    @Test
    void suggestedJvmOptionsContainsRequiredFlags() throws Exception {
        MockMvc mvc = memorySetup(new MemoryReportProvider(new MemoryCalculator(JDK_25)))
                .build();

        mvc.perform(get("/bootui/api/live-memory")
                        .param("totalMemoryMb", "1024")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedJvmOptions").value(org.hamcrest.Matchers.containsString("-Xmx")))
                .andExpect(jsonPath("$.suggestedJvmOptions").value(org.hamcrest.Matchers.containsString("-Xms")))
                .andExpect(jsonPath("$.suggestedJvmOptions")
                        .value(org.hamcrest.Matchers.containsString("-XX:MaxMetaspaceSize=")))
                .andExpect(jsonPath("$.suggestedJvmOptions")
                        .value(org.hamcrest.Matchers.containsString("-XX:+UseCompactObjectHeaders")));
    }

    @Test
    void jvmInputArgumentsAreExposedAsArray() throws Exception {
        MockMvc mvc = memorySetup(new MemoryReportProvider(new MemoryCalculator(JDK_25)))
                .build();

        // The endpoint always returns the live JVM input args list;
        // in a test JVM it may be empty, but the field must be a JSON array.
        mvc.perform(get("/bootui/api/live-memory").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jvmInputArguments").isArray());
    }

    @Test
    void calculationReportsLiveContextValues() throws Exception {
        MockMvc mvc = memorySetup(new MemoryReportProvider(new MemoryCalculator(JDK_25)))
                .build();

        mvc.perform(get("/bootui/api/live-memory").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // liveThreadCount must be at least 1 (the test thread itself)
                .andExpect(jsonPath("$.calculation.liveThreadCount").value(org.hamcrest.Matchers.greaterThan(0)))
                // liveLoadedClassCount must be at least 1
                .andExpect(jsonPath("$.calculation.liveLoadedClassCount").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void kubernetesRecommendationReportsGuaranteedResources() throws Exception {
        MockMvc mvc = memorySetup(
                        new MemoryReportProvider(new MemoryCalculator(JDK_25), ContainerMemoryLimitDetector.disabled()))
                .build();

        long expectedBytes = 1024L * 1024L * 1024L;
        mvc.perform(get("/bootui/api/live-memory")
                        .param("totalMemoryMb", "1024")
                        .param("headRoomPercent", "10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kubernetes.requestMemoryBytes").value(expectedBytes))
                .andExpect(jsonPath("$.kubernetes.limitMemoryBytes").value(expectedBytes))
                .andExpect(jsonPath("$.kubernetes.qosClass").value("Guaranteed"))
                .andExpect(jsonPath("$.kubernetes.burstableEnabled").value(false))
                .andExpect(jsonPath("$.kubernetes.actuatorProbesEnabled").value(true))
                .andExpect(jsonPath("$.kubernetes.maxRamPercentage").isNumber())
                .andExpect(jsonPath("$.kubernetes.yaml").value(org.hamcrest.Matchers.containsString("resources:")))
                .andExpect(
                        jsonPath("$.kubernetes.yaml").value(org.hamcrest.Matchers.containsString("MaxRAMPercentage")))
                .andExpect(jsonPath("$.kubernetes.yaml").value(org.hamcrest.Matchers.containsString("startupProbe")))
                .andExpect(jsonPath("$.kubernetes.yaml").value(org.hamcrest.Matchers.containsString("readinessProbe")))
                .andExpect(jsonPath("$.kubernetes.yaml")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("-Xmx"))))
                .andExpect(jsonPath("$.kubernetes.yaml")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("-Xms"))));
    }

    @Test
    void jvmTuningDetectedVirtualThreadsChangeStackSizingWithoutSettingSpringProperty() throws Exception {
        MockEnvironment enabledEnvironment =
                new MockEnvironment().withProperty("spring.threads.virtual.enabled", "true");
        MockMvc enabledMvc = memorySetup(new MemoryReportProvider(
                        new MemoryCalculator(JDK_25), ContainerMemoryLimitDetector.disabled(), enabledEnvironment))
                .build();

        enabledMvc
                .perform(get("/bootui/api/jvm-tuning")
                        .param("totalMemoryMb", "1024")
                        .param("threadCount", "250")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculation.virtualThreadsEnabled").value(true))
                .andExpect(jsonPath("$.calculation.stackBytesPerThread").value(512L * 1024L))
                .andExpect(jsonPath("$.suggestedJvmOptions")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("spring.threads.virtual.enabled"))))
                .andExpect(jsonPath("$.kubernetes.javaToolOptions")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("spring.threads.virtual.enabled"))))
                .andExpect(jsonPath("$.kubernetes.javaToolOptions")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("-Xmx"))))
                .andExpect(jsonPath("$.kubernetes.javaToolOptions")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("-Xms"))));

        MockEnvironment disabledEnvironment =
                new MockEnvironment().withProperty("spring.threads.virtual.enabled", "false");
        MockMvc disabledMvc = memorySetup(new MemoryReportProvider(
                        new MemoryCalculator(JDK_25), ContainerMemoryLimitDetector.disabled(), disabledEnvironment))
                .build();

        disabledMvc
                .perform(get("/bootui/api/jvm-tuning")
                        .param("totalMemoryMb", "1024")
                        .param("threadCount", "250")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculation.virtualThreadsEnabled").value(false))
                .andExpect(jsonPath("$.calculation.stackBytesPerThread").value(1024L * 1024L))
                .andExpect(jsonPath("$.suggestedJvmOptions")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("spring.threads.virtual.enabled"))));
    }

    @Test
    void jvmTuningVirtualThreadsParamDoesNotOverrideApplicationState() throws Exception {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.threads.virtual.enabled", "false");
        MockMvc mvc = memorySetup(new MemoryReportProvider(
                        new MemoryCalculator(JDK_25), ContainerMemoryLimitDetector.disabled(), environment))
                .build();

        mvc.perform(get("/bootui/api/jvm-tuning")
                        .param("totalMemoryMb", "1024")
                        .param("virtualThreadsEnabled", "true")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculation.virtualThreadsEnabled").value(false))
                .andExpect(jsonPath("$.calculation.stackBytesPerThread").value(1024L * 1024L))
                .andExpect(jsonPath("$.suggestedJvmOptions")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("spring.threads.virtual.enabled"))));
    }

    @Test
    void jvmTuningDoesNotEnableVirtualThreadsWhenPropertyIsAbsent() throws Exception {
        MockMvc mvc = memorySetup(new MemoryReportProvider(
                        new MemoryCalculator(JDK_25), ContainerMemoryLimitDetector.disabled(), new MockEnvironment()))
                .build();

        mvc.perform(get("/bootui/api/jvm-tuning").param("totalMemoryMb", "1024").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculation.virtualThreadsEnabled").value(false))
                .andExpect(jsonPath("$.suggestedJvmOptions")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("spring.threads.virtual.enabled"))));
    }

    @Test
    void kubernetesBurstableParamChangesRequestAndQos() throws Exception {
        MockMvc mvc = memorySetup(
                        new MemoryReportProvider(new MemoryCalculator(JDK_25), ContainerMemoryLimitDetector.disabled()))
                .build();

        mvc.perform(get("/bootui/api/jvm-tuning")
                        .param("totalMemoryMb", "4096")
                        .param("kubernetesBurstableEnabled", "true")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kubernetes.burstableEnabled").value(true))
                .andExpect(jsonPath("$.kubernetes.qosClass").value("Burstable"))
                .andExpect(jsonPath("$.kubernetes.limitMemory").value("4096Mi"))
                .andExpect(jsonPath("$.kubernetes.requestMemory").value(org.hamcrest.Matchers.not("4096Mi")))
                .andExpect(jsonPath("$.kubernetes.yaml").value(org.hamcrest.Matchers.containsString("memory: \"")))
                .andExpect(jsonPath("$.kubernetes.yaml").value(org.hamcrest.Matchers.containsString("limits:")));
    }

    @Test
    void kubernetesActuatorToggleUsesApplicationConfigAndCanBeOverridden() throws Exception {
        MockEnvironment environment =
                new MockEnvironment().withProperty("management.endpoint.health.probes.enabled", "false");
        MockMvc mvc = memorySetup(new MemoryReportProvider(
                        new MemoryCalculator(JDK_25), ContainerMemoryLimitDetector.disabled(), environment))
                .build();

        mvc.perform(get("/bootui/api/jvm-tuning").param("totalMemoryMb", "1024").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kubernetes.actuatorProbesEnabled").value(false))
                .andExpect(jsonPath("$.kubernetes.yaml")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("startupProbe"))))
                .andExpect(jsonPath("$.kubernetes.yaml")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED"))));

        mvc.perform(get("/bootui/api/jvm-tuning")
                        .param("totalMemoryMb", "1024")
                        .param("kubernetesActuatorEnabled", "true")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kubernetes.actuatorProbesEnabled").value(true))
                .andExpect(jsonPath("$.kubernetes.yaml").value(org.hamcrest.Matchers.containsString("startupProbe")))
                .andExpect(jsonPath("$.kubernetes.yaml")
                        .value(org.hamcrest.Matchers.containsString("MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED")));
    }

    private static StandaloneMockMvcBuilder memorySetup(MemoryReportProvider provider) {
        return standaloneSetup(new LiveMemoryController(provider), new JvmTuningController(provider));
    }
}
