package io.github.jdubois.bootui.engine.archunit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.architecture.locationfixtures.LocatedStreamUser;
import java.net.URI;
import org.junit.jupiter.api.Test;

class KotlinSourceMapTests {

    private static final String SMAP = """
            SMAP
            Orders.kt
            Kotlin
            *S Kotlin
            *F
            + 1 Orders.kt
            com/example/Orders
            + 2 Helpers.kt
            com/example/HelpersKt
            *L
            1#1,24:1
            6#2,2:25
            *S KotlinDebug
            *F
            + 1 Orders.kt
            com/example/Orders
            *L
            11#1:25,2
            *E
            """;

    @Test
    void keepsLinesOfTheDeclaringFileAndRejectsInlinedLinesOfAnotherFile() {
        KotlinSourceMap map = KotlinSourceMap.parse(SMAP, "Orders.kt");

        assertThat(map).isNotNull();
        assertThat(map.declaringLine(7)).hasValue(7);
        assertThat(map.declaringLine(24)).hasValue(24);
        assertThat(map.declaringLine(25)).isEmpty();
        assertThat(map.declaringLine(26)).isEmpty();
        assertThat(map.declaringLine(99)).isEmpty();
    }

    @Test
    void mapsShiftedRangesBackToTheirInputLine() {
        String smap = "SMAP\nA.kt\nKotlin\n*S Kotlin\n*F\n1 A.kt\n*L\n10#1,3:40,2\n*E\n";
        KotlinSourceMap map = KotlinSourceMap.parse(smap, "A.kt");

        assertThat(map.declaringLine(40)).hasValue(10);
        assertThat(map.declaringLine(43)).hasValue(11);
        assertThat(map.declaringLine(45)).hasValue(12);
        assertThat(map.declaringLine(46)).isEmpty();
    }

    @Test
    void refusesMapsItCannotTrust() {
        assertThat(KotlinSourceMap.parse(null, "Orders.kt")).isNull();
        assertThat(KotlinSourceMap.parse(SMAP, "Other.kt")).isNull();
        assertThat(KotlinSourceMap.parse("not a map", "Orders.kt")).isNull();
        assertThat(KotlinSourceMap.parse(SMAP.replace("1#1,24:1", "x#1,24:1"), "Orders.kt"))
                .isNull();
        assertThat(KotlinSourceMap.parse(SMAP.replace("1#1,24:1", "1#1,0:1"), "Orders.kt"))
                .isNull();
    }

    @Test
    void readsTheRecordedSourceFileAndSourceMapOfRealClassFiles() throws Exception {
        URI kotlin = KotlinSourceMapTests.class
                .getResource(
                        "/io/github/jdubois/bootui/engine/architecture/kotlinlocationfixtures/KotlinStreamUser.class")
                .toURI();
        ClassFileFacts facts = ClassFileFacts.read(kotlin).orElseThrow();
        assertThat(facts.sourceFile()).isEqualTo("KotlinLocationFixtures.kt");
        assertThat(facts.sourceMap()).startsWith("SMAP\nKotlinLocationFixtures.kt\nKotlin\n");
        KotlinSourceMap map = KotlinSourceMap.parse(facts.sourceMap(), facts.sourceFile());
        assertThat(map.declaringLine(7)).hasValue(7);
        assertThat(map.declaringLine(25)).isEmpty();

        URI java = LocatedStreamUser.class
                .getResource(LocatedStreamUser.class.getSimpleName() + ".class")
                .toURI();
        ClassFileFacts javaFacts = ClassFileFacts.read(java).orElseThrow();
        assertThat(javaFacts.sourceFile()).isEqualTo("LocatedStreamUser.java");
        assertThat(javaFacts.sourceMap()).isNull();

        assertThat(ClassFileFacts.read(URI.create("quarkus:/io/example/Missing.class")))
                .isEmpty();
        assertThat(ClassFileFacts.read((URI) null)).isEmpty();
        assertThat(ClassFileFacts.read((java.io.InputStream) null)).isEmpty();
    }
}
