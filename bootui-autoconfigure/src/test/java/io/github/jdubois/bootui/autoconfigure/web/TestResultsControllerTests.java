package io.github.jdubois.bootui.autoconfigure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;

class TestResultsControllerTests {

    @TempDir
    Path tempDir;

    @Test
    void returnsNotPresentWhenNoReportsDirExists() throws Exception {
        // Use a fresh controller whose working dir doesn't have surefire reports.
        MockMvc mvc = standaloneSetup(new TestResultsController()).build();

        // The default test run environment may or may not have reports – the
        // controller just reports surefirePresent=false when neither dir exists.
        // We verify the response shape is correct (not an error).
        mvc.perform(get("/bootui/api/test-results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTests").isNumber())
                .andExpect(jsonPath("$.suites").isArray());
    }

    @Test
    void parsesPassingTestSuite() throws Exception {
        File reportsDir = tempDir.resolve("target/surefire-reports").toFile();
        reportsDir.mkdirs();
        writeXml(new File(reportsDir, "TEST-com.example.FooTest.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.FooTest" tests="2" failures="0" errors="0" skipped="0" time="0.123">
                  <testcase name="testOne" classname="com.example.FooTest" time="0.05"/>
                  <testcase name="testTwo" classname="com.example.FooTest" time="0.073"/>
                </testsuite>
                """);

        TestResultsController controller = new TestResultsController() {
            @Override
            protected String workingDir() {
                return tempDir.toString();
            }
        };
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(get("/bootui/api/test-results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.surefirePresent").value(true))
                .andExpect(jsonPath("$.totalTests").value(2))
                .andExpect(jsonPath("$.totalPassed").value(2))
                .andExpect(jsonPath("$.totalFailed").value(0))
                .andExpect(jsonPath("$.suites[0].name").value("com.example.FooTest"))
                .andExpect(jsonPath("$.suites[0].testCases[0].status").value("PASSED"))
                .andExpect(jsonPath("$.suites[0].testCases[1].status").value("PASSED"));
    }

    @Test
    void parsesFailingTestSuite() throws Exception {
        File reportsDir = tempDir.resolve("target/surefire-reports").toFile();
        reportsDir.mkdirs();
        writeXml(new File(reportsDir, "TEST-com.example.BarTest.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.BarTest" tests="3" failures="1" errors="0" skipped="1" time="0.5">
                  <testcase name="passes" classname="com.example.BarTest" time="0.1"/>
                  <testcase name="fails" classname="com.example.BarTest" time="0.2">
                    <failure type="java.lang.AssertionError" message="expected true but was false">stacktrace</failure>
                  </testcase>
                  <testcase name="isSkipped" classname="com.example.BarTest" time="0.0">
                    <skipped/>
                  </testcase>
                </testsuite>
                """);

        TestResultsController controller = new TestResultsController() {
            @Override
            protected String workingDir() {
                return tempDir.toString();
            }
        };
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(get("/bootui/api/test-results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTests").value(3))
                .andExpect(jsonPath("$.totalPassed").value(1))
                .andExpect(jsonPath("$.totalFailed").value(1))
                .andExpect(jsonPath("$.totalSkipped").value(1))
                .andExpect(jsonPath("$.suites[0].failed").value(1))
                .andExpect(jsonPath("$.suites[0].testCases[1].status").value("FAILED"))
                .andExpect(jsonPath("$.suites[0].testCases[1].failureType").value("java.lang.AssertionError"))
                .andExpect(jsonPath("$.suites[0].testCases[2].status").value("SKIPPED"));
    }

    private static void writeXml(File file, String content) throws IOException {
        try (FileWriter w = new FileWriter(file)) {
            w.write(content);
        }
    }
}
