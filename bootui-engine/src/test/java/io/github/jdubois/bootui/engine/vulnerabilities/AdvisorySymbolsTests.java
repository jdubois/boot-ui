package io.github.jdubois.bootui.engine.vulnerabilities;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DependencyVulnerabilityDto;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdvisorySymbolsTests {

    @Test
    void structuredSymbolsAreNormalizedToBinaryNames() {
        assertThat(AdvisorySymbols.normalize("org.yaml.snakeyaml.constructor.Constructor"))
                .isEqualTo("org.yaml.snakeyaml.constructor.Constructor");
        assertThat(AdvisorySymbols.normalize("org/yaml/snakeyaml/Yaml")).isEqualTo("org.yaml.snakeyaml.Yaml");
        assertThat(AdvisorySymbols.normalize("org.yaml.snakeyaml.Yaml#load")).isEqualTo("org.yaml.snakeyaml.Yaml#load");
        assertThat(AdvisorySymbols.normalize("org.yaml.snakeyaml.Yaml::load"))
                .isEqualTo("org.yaml.snakeyaml.Yaml#load");
        assertThat(AdvisorySymbols.normalize("org.yaml.snakeyaml.Yaml.load(java.lang.String)"))
                .isEqualTo("org.yaml.snakeyaml.Yaml#load");
        assertThat(AdvisorySymbols.normalize("io.netty.handler.codec.http.HttpObjectDecoder.HeaderParser"))
                .isEqualTo("io.netty.handler.codec.http.HttpObjectDecoder$HeaderParser");
        assertThat(AdvisorySymbols.normalize("io.netty.handler.codec.http.HttpObjectDecoder$HeaderParser"))
                .isEqualTo("io.netty.handler.codec.http.HttpObjectDecoder$HeaderParser");
        assertThat(AdvisorySymbols.normalize(" com.example.Type ")).isEqualTo("com.example.Type");
        assertThat(AdvisorySymbols.normalize(
                        "com.fasterxml.jackson.core.StreamReadConstraints$DEFAULT_MAX_STRING_LENGTH"))
                .as("a constant names its class")
                .isEqualTo("com.fasterxml.jackson.core.StreamReadConstraints");
        assertThat(AdvisorySymbols.normalize("com.example.Type.MAX_LENGTH")).isEqualTo("com.example.Type");
    }

    @Test
    void anEscapedLineBreakDoesNotGlueALetterToAClassName() {
        AdvisorySymbols.Symbols symbols =
                AdvisorySymbols.of(List.of(), null, "Limits:\\ncom.fasterxml.jackson.core.io.NumberInput is slow");

        assertThat(symbols.symbols()).isEmpty();
    }

    @Test
    void whatNamesNoTypeInAPackageIsDropped() {
        assertThat(AdvisorySymbols.normalize(null)).isNull();
        assertThat(AdvisorySymbols.normalize("")).isNull();
        assertThat(AdvisorySymbols.normalize("Yaml")).isNull();
        assertThat(AdvisorySymbols.normalize("org.yaml.snakeyaml")).isNull();
        assertThat(AdvisorySymbols.normalize("org.yaml.snakeyaml.Yaml.load.more"))
                .isNull();
        assertThat(AdvisorySymbols.normalize("java.lang.String"))
                .as("no library defines java.")
                .isNull();
        assertThat(AdvisorySymbols.normalize("org.example.Type#9bad")).isEqualTo("org.example.Type");
    }

    @Test
    void structuredSymbolsWinOverTheText() {
        AdvisorySymbols.Symbols symbols = AdvisorySymbols.of(
                List.of("org.example.Parser#parse", "not a symbol"), "Calling org.example.Other is unsafe", null);

        assertThat(symbols.source()).isEqualTo("OSV");
        assertThat(symbols.symbols()).containsExactly("org.example.Parser#parse");
    }

    @Test
    void textClassNamesAreUsedWhenNothingIsStructuredAndMarkedAsSuch() {
        AdvisorySymbols.Symbols symbols = AdvisorySymbols.of(
                List.of(), "Netty's io.netty.handler.codec.http.multipart.HttpPostRequestEncoder is vulnerable", """
                        PoC: `io.netty.buffer.Unpooled.copiedBuffer(...)`, then call
                        io.netty.handler.codec.http.HttpRequestEncoder#encode(ctx). Not a class:
                        https://github.com/netty/netty/security.Advisory or java.lang.String or e.g. Foo.Bar.
                        """);

        assertThat(symbols.source()).isEqualTo("ADVISORY_TEXT");
        assertThat(symbols.symbols())
                .containsExactly(
                        "io.netty.buffer.Unpooled#copiedBuffer",
                        "io.netty.handler.codec.http.HttpRequestEncoder#encode",
                        "io.netty.handler.codec.http.multipart.HttpPostRequestEncoder");
    }

    @Test
    void anAdvisoryNamingNothingSaysSo() {
        AdvisorySymbols.Symbols symbols = AdvisorySymbols.of(
                null, "jackson-databind allows a stack overflow", "A large depth of nested objects.");

        assertThat(symbols.source()).isEqualTo("NONE");
        assertThat(symbols.symbols()).isEmpty();
    }

    @Test
    void symbolsAreBounded() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            many.add("org.example.Type" + i);
        }

        assertThat(AdvisorySymbols.of(many, null, null).symbols()).hasSize(AdvisorySymbols.MAX_SYMBOLS);
    }

    @Test
    void anImportsSymbolIsQualifiedByItsPath() {
        assertThat(AdvisorySymbols.qualified("org.example", "Parser.parse")).isEqualTo("org.example.Parser.parse");
        assertThat(AdvisorySymbols.qualified("org.example", "org.example.Parser"))
                .isEqualTo("org.example.Parser");
        assertThat(AdvisorySymbols.qualified(null, "org.example.Parser")).isEqualTo("org.example.Parser");
        assertThat(AdvisorySymbols.className("org.example.Parser#parse")).isEqualTo("org.example.Parser");
        assertThat(AdvisorySymbols.className("org.example.Parser")).isEqualTo("org.example.Parser");
    }

    @Test
    void annotatingAnAdvisoryChangesNothingElse() {
        DependencyVulnerabilityDto advisory = new DependencyVulnerabilityDto(
                "GHSA-1", "In org.example.Parser", "details", "HIGH", 7.5, List.of(), List.of(), List.of("2.0"), true);

        DependencyVulnerabilityDto annotated = AdvisorySymbols.annotate(advisory, List.of());

        assertThat(annotated.advisorySymbols()).containsExactly("org.example.Parser");
        assertThat(annotated.advisorySymbolSource()).isEqualTo(DependencyVulnerabilityDto.SYMBOLS_FROM_TEXT);
        assertThat(annotated.withAdvisorySymbols(List.of(), null)).isEqualTo(advisory);
    }
}
