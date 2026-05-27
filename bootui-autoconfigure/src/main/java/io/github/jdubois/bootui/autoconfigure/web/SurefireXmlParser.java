package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.BootUiDtos.TestResultDto;
import io.github.jdubois.bootui.core.BootUiDtos.TestSuiteDto;
import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Parses Maven Surefire / Failsafe XML report files into {@link TestSuiteDto} objects.
 *
 * <p>The Surefire report format follows the de-facto JUnit XML schema:
 * {@code <testsuite tests="N" failures="N" errors="N" skipped="N" time="N.NNN">}
 * with nested {@code <testcase>} elements that optionally carry a {@code <failure>},
 * {@code <error>}, or {@code <skipped>} child element.</p>
 */
final class SurefireXmlParser {

    private SurefireXmlParser() {
    }

    /**
     * Parses a single Surefire XML report file.
     *
     * @param file a {@code TEST-*.xml} file from the Surefire reports directory
     * @return the parsed {@link TestSuiteDto}, or {@code null} if the file cannot be parsed
     */
    static TestSuiteDto parse(File file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(file);

            Element suite = doc.getDocumentElement();
            if (!"testsuite".equals(suite.getTagName())) {
                return null;
            }

            String name = attr(suite, "name", file.getName());
            int tests = intAttr(suite, "tests", 0);
            int failures = intAttr(suite, "failures", 0);
            int errors = intAttr(suite, "errors", 0);
            int skipped = intAttr(suite, "skipped", 0);
            long durationMs = secondsToMs(attr(suite, "time", "0"));
            int failed = failures + errors;
            int passed = Math.max(0, tests - failed - skipped);

            List<TestResultDto> testCases = new ArrayList<>();
            NodeList caseNodes = suite.getElementsByTagName("testcase");
            for (int i = 0; i < caseNodes.getLength(); i++) {
                Element tc = (Element) caseNodes.item(i);
                testCases.add(parseTestCase(tc));
            }

            return new TestSuiteDto(name, tests, passed, failed, skipped, durationMs, testCases);
        }
        catch (Exception ex) {
            return null;
        }
    }

    private static TestResultDto parseTestCase(Element tc) {
        String className = attr(tc, "classname", "");
        String testName = attr(tc, "name", "");
        long durationMs = secondsToMs(attr(tc, "time", "0"));

        Element failure = firstChild(tc, "failure");
        Element error = firstChild(tc, "error");
        Element skipped = firstChild(tc, "skipped");

        String status;
        String failureMessage = null;
        String failureType = null;

        if (failure != null) {
            status = "FAILED";
            failureMessage = attr(failure, "message", failure.getTextContent().trim());
            failureType = attr(failure, "type", null);
        }
        else if (error != null) {
            status = "ERROR";
            failureMessage = attr(error, "message", error.getTextContent().trim());
            failureType = attr(error, "type", null);
        }
        else if (skipped != null) {
            status = "SKIPPED";
        }
        else {
            status = "PASSED";
        }

        return new TestResultDto(className, testName, status, durationMs, failureMessage, failureType);
    }

    private static Element firstChild(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    private static String attr(Element el, String name, String defaultValue) {
        String v = el.getAttribute(name);
        return (v == null || v.isBlank()) ? defaultValue : v.trim();
    }

    private static int intAttr(Element el, String name, int defaultValue) {
        try {
            return Integer.parseInt(attr(el, name, String.valueOf(defaultValue)));
        }
        catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static long secondsToMs(String seconds) {
        try {
            return new BigDecimal(seconds).multiply(BigDecimal.valueOf(1000))
                    .setScale(0, RoundingMode.HALF_UP).longValue();
        }
        catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
