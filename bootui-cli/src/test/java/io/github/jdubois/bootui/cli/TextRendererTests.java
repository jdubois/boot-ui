package io.github.jdubois.bootui.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.client.JsonValue;
import org.junit.jupiter.api.Test;

/**
 * Pins the readable rendering.
 *
 * <p>The renderer has to work on payloads it knows nothing about, since the CLI treats responses as opaque so
 * it keeps working across BootUI versions. These tests fix the inference rules it uses to decide what a shape
 * means.
 */
class TextRendererTests {

    private final TextRenderer renderer = new TextRenderer(false);

    @Test
    void scalarMembersRenderAsKeyValueLines() {
        String text = render("{\"uptime\":\"3h\",\"pid\":42,\"healthy\":true}");

        assertThat(text).isEqualTo("uptime: 3h\npid: 42\nhealthy: true");
    }

    @Test
    void anArrayOfLikeShapedObjectsBecomesATable() {
        String text = render("{\"beans\":[{\"name\":\"a\",\"scope\":\"singleton\"},"
                + "{\"name\":\"bb\",\"scope\":\"prototype\"}]}");

        assertThat(text.lines().toList())
                .containsExactly(
                        "beans (2)", "  name  scope", "  ----  ---------", "  a     singleton", "  bb    prototype");
    }

    @Test
    void anArrayWithANestedObjectFallsBackToATreeRatherThanLyingInATable() {
        String text = render("{\"items\":[{\"name\":\"a\",\"detail\":{\"x\":1}}]}");

        assertThat(text.lines().toList())
                .containsExactly("items (1)", "  -", "    name: a", "    detail", "      x: 1");
    }

    @Test
    void anArrayOfScalarsRendersAsAList() {
        String text = render("{\"profiles\":[\"dev\",\"test\"]}");

        assertThat(text).isEqualTo("profiles (2)\n  - dev\n  - test");
    }

    @Test
    void nullAndAbsentValuesReadAsADashRatherThanTheWordNull() {
        String text = render("{\"cause\":null}");

        assertThat(text).isEqualTo("cause: -");
    }

    @Test
    void emptyCollectionsStayVisibleInsteadOfDisappearing() {
        String text = render("{\"findings\":[],\"details\":{}}");

        assertThat(text).isEqualTo("findings: []\ndetails: {}");
    }

    @Test
    void numbersKeepTheFormTheServerSentThem() {
        // Re-formatting would turn a version like 1.10 into 1.1 and a byte count into scientific notation.
        String text = render("{\"ratio\":1.50,\"bytes\":12345678901}");

        assertThat(text).isEqualTo("ratio: 1.50\nbytes: 12345678901");
    }

    @Test
    void longCellsAreTruncatedSoATableStaysReadable() {
        String stack = "a".repeat(200);
        String text = render("{\"rows\":[{\"trace\":\"" + stack + "\"}]}");

        assertThat(text).contains("\u2026");
        assertThat(text.lines().toList())
                .allSatisfy(line -> assertThat(line.length()).isLessThan(80));
    }

    @Test
    void valuesOutsideATableArePrintedWhole() {
        String reason = "repeated-selects did not run: " + "its external validation is not complete yet ".repeat(3)
                + "so this check stays out of the default list.";
        String text = render("{\"summary\":\"" + reason + "\",\"checksNotRun\":[\"" + reason + "\"]}");

        assertThat(text).doesNotContain("\u2026");
        assertThat(text).isEqualTo("summary: " + reason + "\nchecksNotRun (1)\n  - " + reason);
    }

    @Test
    void newlinesInsideACellDoNotBreakTheTable() {
        String text = render("{\"rows\":[{\"message\":\"first\\nsecond\"}]}");

        assertThat(text.lines().toList()).containsExactly("rows (1)", "  message", "  ------------", "  first second");
    }

    @Test
    void nestedObjectsAreIndentedUnderTheirName() {
        String text = render("{\"jvm\":{\"vendor\":\"Eclipse\",\"version\":\"21\"}}");

        assertThat(text).isEqualTo("jvm\n  vendor: Eclipse\n  version: 21");
    }

    @Test
    void aTopLevelArrayIsRenderedWithoutAWrapper() {
        String text = renderer.render(JsonValue.parse("[{\"a\":1},{\"a\":2}]"));

        assertThat(text.lines().toList()).containsExactly("a", "-", "1", "2");
    }

    @Test
    void colourIsOnlyAddedWhenAskedFor() {
        String plain = new TextRenderer(false).render(JsonValue.parse("{\"a\":1}"));
        String coloured = new TextRenderer(true).render(JsonValue.parse("{\"a\":1}"));

        assertThat(plain).doesNotContain("\u001B[");
        assertThat(coloured).contains("\u001B[");
    }

    private String render(String json) {
        return renderer.render(JsonValue.parse(json));
    }

    @Test
    void controlCharactersInValuesCannotReachTheTerminal() {
        // A payload carries application-controlled text. Left alone, this would retitle the window, colour
        // the output despite --no-color, and use backspaces to hide what it actually contains.
        String text = render("{\"note\":\"\\u001b]0;pwned\\u0007\\u001b[31mred\\u001b[0m a\\b\\b\\bzap\"}");

        assertThat(text).doesNotContain("\u001B").doesNotContain("\u0007").doesNotContain("\b");
        assertThat(text).isEqualTo("note:  ]0;pwned  [31mred [0m a   zap");
    }

    @Test
    void controlCharactersInKeysCannotReachTheTerminalEither() {
        String text = render("{\"a\\u001b[2Jb\":\"1\"}");

        assertThat(text).doesNotContain("\u001B").isEqualTo("a [2Jb: 1");
    }

    @Test
    void advisorViolationLocationsStayReadableBesideTheirViolations() {
        String text = render("{\"violations\":[\"Method <a.B.place()> calls System.out\",\"Cycle a -> b\"],"
                + "\"locations\":[{\"className\":\"a.B\",\"memberName\":\"place\",\"kind\":\"METHOD\","
                + "\"sourceFile\":\"B.java\",\"line\":42,\"sourcePath\":\"/work/B.java\",\"precision\":\"LINE\"},"
                + "null]}");

        assertThat(text)
                .contains("violations (2)", "- Method <a.B.place()> calls System.out", "- Cycle a -> b")
                .contains("locations (2)", "className: a.B", "memberName: place", "line: 42")
                .contains("sourcePath: /work/B.java", "precision: LINE")
                .contains("  - -");
    }
}
