package io.github.jdubois.bootui.autoconfigure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.web.MemoryCalculator.JdkVersion;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-level tests for {@link MemoryController}.
 *
 * <p>Uses the package-private {@code MemoryController(MemoryCalculator)}
 * constructor to inject a {@link MemoryCalculator} with a pinned JDK version
 * so that JVM-option assertions are reproducible regardless of the host JDK.
 * Live {@link java.lang.management.ManagementFactory} data is used for heap,
 * non-heap and pool readings — these will always be non-negative in a running
 * JVM, which is all the tests need to assert about them.</p>
 *
 * <p>Does not duplicate the formula assertions already covered by
 * {@link MemoryCalculatorTests}.</p>
 */
class MemoryControllerTests {

    private static final JdkVersion JDK_25 = () -> 25;

    @Test
    void memoryReturnsExpectedTopLevelShape() throws Exception {
        MockMvc mvc = standaloneSetup(new MemoryController(new MemoryCalculator(JDK_25)))
                .build();

        mvc.perform(get("/bootui/api/memory").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heap").isMap())
                .andExpect(jsonPath("$.heap.name").value("Heap"))
                .andExpect(jsonPath("$.nonHeap").isMap())
                .andExpect(jsonPath("$.nonHeap.name").value("Non-Heap"))
                .andExpect(jsonPath("$.pools").isArray())
                .andExpect(jsonPath("$.jvmInputArguments").isArray())
                .andExpect(jsonPath("$.suggestedJvmOptions").isString())
                .andExpect(jsonPath("$.calculation").isMap());
    }

    @Test
    void memoryPoolDtoHasRequiredFields() throws Exception {
        MockMvc mvc = standaloneSetup(new MemoryController(new MemoryCalculator(JDK_25)))
                .build();

        mvc.perform(get("/bootui/api/memory").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heap.usedBytes").isNumber())
                .andExpect(jsonPath("$.heap.committedBytes").isNumber())
                .andExpect(jsonPath("$.heap.maxBytes").isNumber())
                .andExpect(jsonPath("$.heap.usedPercent").isNumber())
                .andExpect(jsonPath("$.nonHeap.usedBytes").isNumber());
    }

    @Test
    void memoryHonorsTotalMemoryMbOverrideParam() throws Exception {
        MockMvc mvc = standaloneSetup(new MemoryController(new MemoryCalculator(JDK_25)))
                .build();

        // With 512 MB total, calculation.totalMemoryBytes must reflect exactly 512 * 1024 * 1024
        long expectedBytes = 512L * 1024L * 1024L;
        mvc.perform(get("/bootui/api/memory").param("totalMemoryMb", "512").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculation.totalMemoryBytes").value(expectedBytes));
    }

    @Test
    void memoryHonorsThreadCountOverrideParam() throws Exception {
        MockMvc mvc = standaloneSetup(new MemoryController(new MemoryCalculator(JDK_25)))
                .build();

        mvc.perform(get("/bootui/api/memory")
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
        MockMvc mvc = standaloneSetup(new MemoryController(new MemoryCalculator(JDK_25)))
                .build();

        mvc.perform(get("/bootui/api/memory").param("totalMemoryMb", "1024").accept(MediaType.APPLICATION_JSON))
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
        MockMvc mvc = standaloneSetup(new MemoryController(new MemoryCalculator(JDK_25)))
                .build();

        // The endpoint always returns the live JVM input args list;
        // in a test JVM it may be empty, but the field must be a JSON array.
        mvc.perform(get("/bootui/api/memory").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jvmInputArguments").isArray());
    }

    @Test
    void calculationReportsLiveContextValues() throws Exception {
        MockMvc mvc = standaloneSetup(new MemoryController(new MemoryCalculator(JDK_25)))
                .build();

        mvc.perform(get("/bootui/api/memory").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // liveThreadCount must be at least 1 (the test thread itself)
                .andExpect(jsonPath("$.calculation.liveThreadCount").value(org.hamcrest.Matchers.greaterThan(0)))
                // liveLoadedClassCount must be at least 1
                .andExpect(jsonPath("$.calculation.liveLoadedClassCount").value(org.hamcrest.Matchers.greaterThan(0)));
    }
}
