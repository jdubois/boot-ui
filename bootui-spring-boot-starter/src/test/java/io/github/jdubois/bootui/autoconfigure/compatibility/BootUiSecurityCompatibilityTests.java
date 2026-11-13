package io.github.jdubois.bootui.autoconfigure.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.aot.AotDetector;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class BootUiSecurityCompatibilityTests {

    @Test
    void checksTheSelectedServletAndReactiveContractsWithoutConstructingSecurityBeans() {
        ConditionContext context = context(getClass().getClassLoader());
        assertThat(new BootUiSecurityCompatibility.Servlet().matches(context, mock(AnnotatedTypeMetadata.class)))
                .isTrue();
        assertThat(new BootUiSecurityCompatibility.Reactive().matches(context, mock(AnnotatedTypeMetadata.class)))
                .isTrue();
    }

    @Test
    void distinguishesAMissingMethodFromMarkerPresence() {
        assertThatThrownBy(() -> BootUiSecurityCompatibility.requireMethod(
                        getClass().getClassLoader(), MissingMethod.class.getName(), "spa", false))
                .isInstanceOf(BootUiCompatibilityException.class)
                .hasMessageContaining("BOOTUI-COMPAT-021")
                .hasMessageContaining("spa");
    }

    @Test
    void generatedRuntimeDoesNotRepeatRegistrationTimeIntrospection() {
        try (var aot = mockStatic(AotDetector.class)) {
            aot.when(AotDetector::useGeneratedArtifacts).thenReturn(true);
            ConditionContext context = context(new ClassLoader(null) {});
            assertThat(new BootUiSecurityCompatibility.Servlet().matches(context, mock(AnnotatedTypeMetadata.class)))
                    .isTrue();
            assertThat(new BootUiSecurityCompatibility.Reactive().matches(context, mock(AnnotatedTypeMetadata.class)))
                    .isTrue();
        }
    }

    private static ConditionContext context(ClassLoader loader) {
        ConditionContext context = mock(ConditionContext.class);
        when(context.getClassLoader()).thenReturn(loader);
        return context;
    }

    public static final class MissingMethod {}
}
