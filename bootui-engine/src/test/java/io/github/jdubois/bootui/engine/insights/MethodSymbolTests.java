package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MethodSymbolTests {

    private static final String KEY = "com.example.Orders$Lines#total(Ljava/lang/String;[IJ)Ljava/math/BigDecimal;";

    @Test
    void everyFormNamesTheSameMethod() {
        for (String form : List.of(
                "Lines#total",
                "Orders$Lines#total",
                "com.example.Orders.Lines#total",
                "com.example.Orders$Lines#total",
                "METHOD com.example.Orders$Lines#total",
                "Lines.total(String, int[], long)",
                "Lines#total(java.lang.String,int...,long)",
                "Lines#total(Ljava/lang/String;[IJ)",
                "Lines#total(Ljava/lang/String;[IJ)Ljava/math/BigDecimal;")) {
            MethodSymbol symbol = MethodSymbol.parse(form, false);
            assertThat(symbol).as(form).isNotNull();
            assertThat(symbol.matchesKey(KEY)).as(form).isTrue();
        }
    }

    @Test
    void parametersAndReturnTypesThatDifferDoNotMatch() {
        for (String form : List.of(
                "Lines#total(String)",
                "Lines#total(String, int, long)",
                "Lines#total(Ljava/lang/String;[IJ)V",
                "Other#total",
                "Lines#count")) {
            assertThat(MethodSymbol.parse(form, false).matchesKey(KEY)).as(form).isFalse();
        }
        assertThat(MethodSymbol.parse("Repo#find(java.util.List<Long>)", false)
                        .matchesKey("com.example.Repo#find(Ljava/util/List;)V"))
                .as("generics are left out")
                .isTrue();
        assertThat(MethodSymbol.parse("Repo#put(Map.Entry)", false)
                        .matchesKey("com.example.Repo#put(Ljava/util/Map$Entry;)V"))
                .as("a nested type by its outer class")
                .isTrue();
        assertThat(MethodSymbol.parse("Repo#put(ntry)", false)
                        .matchesKey("com.example.Repo#put(Ljava/util/Map$Entry;)V"))
                .as("only on a dot")
                .isFalse();
        assertThat(MethodSymbol.parse("Repo#find()", false).matchesKey("com.example.Repo#find()V"))
                .isTrue();
        assertThat(MethodSymbol.parse("Repo#find()", false).matchesKey("com.example.Repo#find(I)V"))
                .isFalse();
    }

    @Test
    void aDottedNameIsAMethodOnlyWhenAsked() {
        assertThat(MethodSymbol.parse("ProductService.findAll", false)).isNull();
        assertThat(MethodSymbol.parse("ProductService.findAll", true))
                .isEqualTo(new MethodSymbol("ProductService", "findAll", null, null));
        assertThat(MethodSymbol.parse("api.example.com", true).type()).isEqualTo("api.example");
        for (String notAMethod : List.of(
                "productRepository", "TABLE sample_products", "a#b#c", "Foo#", "#bar", "Foo#bar(", "GET /api/x")) {
            assertThat(MethodSymbol.parse(notAMethod, true)).as(notAMethod).isNull();
        }
    }

    @Test
    void aCandidateLabelNamesItsClassExactly() {
        MethodSymbol symbol = MethodSymbol.parse("Lines#total(String, int[], long)", false);
        assertThat(symbol.label("com.example.Orders$Lines"))
                .isEqualTo("METHOD com.example.Orders$Lines#total(String, int[], long)");
        assertThat(MethodSymbol.parse(symbol.label("com.example.Orders$Lines"), false)
                        .matchesKey(KEY))
                .isTrue();
    }
}
