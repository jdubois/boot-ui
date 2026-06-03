package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.BootUiDtos.PanelDto;
import io.github.jdubois.bootui.core.BootUiDtos.StartupStepDto;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

class BootUiRuntimeHintsTests {

    private final RuntimeHints hints = new RuntimeHints();

    BootUiRuntimeHintsTests() {
        new BootUiRuntimeHints().registerHints(hints, getClass().getClassLoader());
    }

    @Test
    void registersClasspathResourcePatternsScannedAtRuntime() {
        assertThat(RuntimeHintsPredicates.resource().forResource("META-INF/maven/group/artifact/pom.properties"))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.resource().forResource("META-INF/spring-configuration-metadata.json"))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.resource().forResource("bootui-version.properties"))
                .accepts(hints);
    }

    @Test
    void registersHotSpotDiagnosticMxBeanForReflectiveHeapDumps() {
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(TypeReference.of("com.sun.management.HotSpotDiagnosticMXBean"))
                        .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS))
                .accepts(hints);
    }

    @Test
    void registersSpringSecurityTypesInvokedReflectively() {
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(TypeReference.of("org.springframework.security.web.SecurityFilterChain"))
                        .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(TypeReference.of("org.springframework.security.core.GrantedAuthority"))
                        .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(TypeReference.of("org.springframework.security.core.authority.SimpleGrantedAuthority"))
                        .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS))
                .accepts(hints);
    }

    @Test
    void registersBootUiDtosAndArrayTypesForJacksonBinding() {
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(StartupStepDto.class)
                        .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(TypeReference.of(StartupStepDto[].class)))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(TypeReference.of(PanelDto[].class)))
                .accepts(hints);
    }
}
