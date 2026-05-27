package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.BootUiDtos.TestResultsReport;
import io.github.jdubois.bootui.core.BootUiDtos.TestSuiteDto;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes local Maven Surefire / Failsafe test results from the build working directory.
 *
 * <p>Reads {@code TEST-*.xml} files from {@code target/surefire-reports} and
 * {@code target/failsafe-reports} relative to the JVM working directory. These files
 * are written by a preceding Maven test run and are read-only here; no test execution
 * is triggered by this controller.</p>
 */
@RestController
@RequestMapping("/bootui/api/test-results")
public class TestResultsController {

    static final String[] REPORT_DIRS = {
            "target/surefire-reports",
            "target/failsafe-reports"
    };

    @GetMapping
    public TestResultsReport testResults() {
        String base = workingDir();
        for (String relativeDir : REPORT_DIRS) {
            File dir = new File(base, relativeDir);
            if (dir.isDirectory()) {
                return readReports(dir, relativeDir);
            }
        }
        return new TestResultsReport(false, null, 0, 0, 0, 0, List.of());
    }

    /**
     * Returns the working directory used to resolve the Surefire reports path.
     * Overriding this method allows tests to point the controller at a temp directory.
     */
    protected String workingDir() {
        return System.getProperty("user.dir", ".");
    }

    private TestResultsReport readReports(File dir, String reportsDir) {
        File[] xmlFiles = dir.listFiles(f -> f.isFile() && f.getName().startsWith("TEST-") && f.getName().endsWith(".xml"));
        if (xmlFiles == null || xmlFiles.length == 0) {
            return new TestResultsReport(true, reportsDir, 0, 0, 0, 0, List.of());
        }

        List<TestSuiteDto> suites = Arrays.stream(xmlFiles)
                .sorted(Comparator.comparing(File::getName))
                .map(SurefireXmlParser::parse)
                .filter(suite -> suite != null)
                .toList();

        int totalTests = suites.stream().mapToInt(TestSuiteDto::tests).sum();
        int totalPassed = suites.stream().mapToInt(TestSuiteDto::passed).sum();
        int totalFailed = suites.stream().mapToInt(TestSuiteDto::failed).sum();
        int totalSkipped = suites.stream().mapToInt(TestSuiteDto::skipped).sum();

        return new TestResultsReport(true, reportsDir, totalTests, totalPassed, totalFailed, totalSkipped, suites);
    }
}
