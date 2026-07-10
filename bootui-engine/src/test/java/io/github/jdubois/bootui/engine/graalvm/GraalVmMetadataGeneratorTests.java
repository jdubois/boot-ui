package io.github.jdubois.bootui.engine.graalvm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GraalVmMetadataGeneratorTests {

    private final GraalVmMetadataGenerator generator = new GraalVmMetadataGenerator();

    @Test
    void generatesUnifiedReachabilityMetadata() {
        GraalVmMetadata metadata = new GraalVmMetadata(
                List.of("com.example.Person", "com.example.Order"),
                List.of("com.example.Person"),
                List.of("com.example.NativeAccess"),
                List.of("application*.properties"),
                true,
                true,
                true);

        String json = generator.generate(metadata);

        assertThat(json).startsWith("{");
        assertThat(json.strip()).endsWith("}");
        assertBalanced(json);
        assertThat(json).contains("\"comment\"");
        assertThat(json).contains("\"reflection\"");
        assertThat(json)
                .contains("\"condition\": {\"typeReached\": \"com.example.Person\"}")
                .contains("\"type\": \"com.example.Person\"")
                .contains("\"allDeclaredConstructors\": true")
                .contains("\"allDeclaredMethods\": true")
                .contains("\"allDeclaredFields\": true");
        assertThat(json).doesNotContain("\"serialization\"");
        assertThat(json).contains("\"serializable\": true");
        assertThat(json).contains("\"type\": \"com.example.NativeAccess\"");
        assertThat(json).contains("\"jniAccessible\": true");
        assertThat(json).contains("\"resources\"");
        assertThat(json).contains("{\"glob\": \"application*.properties\"}");
        assertThat(json).contains("\"foreign\": {");
        assertThat(json).contains("\"downcalls\": []");
        assertThat(json).contains("\"upcalls\": []");
        assertThat(json).contains("\"directUpcalls\": []");
        assertThat(json).contains("Dynamic proxy calls were detected");
        assertThat(json).contains("Unsafe.allocateInstance calls were detected");
        assertThat(json).contains("FFM Linker usage was detected");
    }

    @Test
    void rendersEmptyArraysWhenCandidateListsAreEmpty() {
        String json = generator.generate(
                new GraalVmMetadata(List.of(), List.of(), List.of(), List.of(), false, false, false));

        assertBalanced(json);
        assertThat(json).contains("\"reflection\": []");
        assertThat(json).doesNotContain("\"serialization\"");
        assertThat(json).contains("\"resources\": []");
        assertThat(json).doesNotContain("\"foreign\"");
    }

    @Test
    void escapesSpecialCharactersInTypeNames() {
        String json = generator.generate(new GraalVmMetadata(
                List.of("com.example.Weird\"\\\n"), List.of(), List.of(), List.of(), false, false, false));

        assertThat(json).contains("com.example.Weird\\\"\\\\\\n");
    }

    private static void assertBalanced(String json) {
        int braces = 0;
        int brackets = 0;
        for (int i = 0; i < json.length(); i++) {
            switch (json.charAt(i)) {
                case '{' -> braces++;
                case '}' -> braces--;
                case '[' -> brackets++;
                case ']' -> brackets--;
                default -> {
                    // not a structural character
                }
            }
        }
        assertThat(braces).as("balanced braces").isZero();
        assertThat(brackets).as("balanced brackets").isZero();
    }
}
