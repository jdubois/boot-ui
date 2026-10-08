package io.github.jdubois.bootui.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link BootUiPathNormalizer}.
 */
class BootUiPathNormalizerTests {

    // --- valid paths ---

    @Test
    void acceptsDefaultPath() {
        assertThat(BootUiPathNormalizer.normalize("/bootui")).isEqualTo("/bootui");
    }

    @Test
    void acceptsCustomPath() {
        assertThat(BootUiPathNormalizer.normalize("/my-console")).isEqualTo("/my-console");
    }

    @Test
    void acceptsNestedPath() {
        assertThat(BootUiPathNormalizer.normalize("/admin/bootui")).isEqualTo("/admin/bootui");
    }

    @Test
    void acceptsRfc3986UnreservedCharacters() {
        assertThat(BootUiPathNormalizer.normalize("/admin_ui/v1.2~preview")).isEqualTo("/admin_ui/v1.2~preview");
    }

    @Test
    void stripsTrailingSlash() {
        assertThat(BootUiPathNormalizer.normalize("/bootui/")).isEqualTo("/bootui");
    }

    @Test
    void stripsMultipleTrailingSlashes() {
        assertThat(BootUiPathNormalizer.normalize("/bootui///")).isEqualTo("/bootui");
    }

    @Test
    void stripsLeadingAndTrailingWhitespace() {
        assertThat(BootUiPathNormalizer.normalize("  /bootui  ")).isEqualTo("/bootui");
    }

    // --- invalid paths ---

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsEmptyString() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsRootPath() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root");
    }

    @Test
    void rejectsPathWithoutLeadingSlash() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("bootui"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'/'");
    }

    @Test
    void rejectsPathWithDotDotTraversal() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/admin/../bootui"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("..");
    }

    @Test
    void rejectsSingleDotPathSegment() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/admin/./bootui"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'.'");
    }

    @Test
    void acceptsDotsWithinAPathSegment() {
        assertThat(BootUiPathNormalizer.normalize("/release..preview")).isEqualTo("/release..preview");
    }

    @Test
    void rejectsPathWithQueryComponent() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/bootui?foo=bar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("?");
    }

    @Test
    void rejectsPathWithFragmentComponent() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/bootui#section"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("#");
    }

    @Test
    void rejectsPathWithEncodedSeparatorLowercase() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/boot%2fui"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("%2F");
    }

    @Test
    void rejectsPathWithEncodedSeparatorUppercase() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/boot%2Fui"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("%2F");
    }

    @Test
    void rejectsPathWithEncodedBackslash() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/boot%5Cui"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("%5C");
    }

    @Test
    void rejectsOtherEncodedCharacters() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/boot%2eui"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only letters");
    }

    @Test
    void rejectsRawBackslash() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/admin\\bootui"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only letters");
    }

    @Test
    void rejectsRoutePatternCharacters() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/admin/{path}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only letters");
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/admin/**"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only letters");
    }

    @Test
    void rejectsInteriorWhitespace() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/admin console"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only letters");
    }

    @Test
    void rejectsPathWithDoubleSlash() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/admin//bootui"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("//");
    }

    @Test
    void rejectsReservedInternalChildPath() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize("/bootui/custom"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bootui.path must not use the reserved internal '/bootui/**' namespace: '/bootui/custom'");
    }

    @Test
    void acceptsDefaultPathSibling() {
        assertThat(BootUiPathNormalizer.normalize("/bootui-console")).isEqualTo("/bootui-console");
    }

    @Test
    void acceptsDefaultApiPathBelowInternalMount() {
        assertThat(BootUiPathNormalizer.normalizeApiPath("/bootui/api/")).isEqualTo("/bootui/api");
    }

    @Test
    void apiPathUsesItsOwnPropertyNameInErrors() {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalizeApiPath("/api/**"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bootui.api-path");
    }

    @ParameterizedTest
    @MethodSource("invalidPathsWithMessages")
    void preservesExactUiAndApiValidationMessages(String path, String message) {
        assertThatThrownBy(() -> BootUiPathNormalizer.normalize(path))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bootui.path" + message);
        assertThatThrownBy(() -> BootUiPathNormalizer.normalizeApiPath(path))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bootui.api-path" + message);
    }

    private static Stream<Arguments> invalidPathsWithMessages() {
        return Stream.of(
                Arguments.of("/", " must not be '/' (the root path would intercept every application request)."),
                Arguments.of("/admin/../bootui", " must not contain '.' or '..' path segments: '/admin/../bootui'"),
                Arguments.of("/bootui?foo=bar", " must not contain a query component ('?'): '/bootui?foo=bar'"),
                Arguments.of("/bootui#section", " must not contain a fragment component ('#'): '/bootui#section'"),
                Arguments.of("/boot%2Fui", " must not contain encoded path separators ('%2F' or '%5C'): '/boot%2Fui'"),
                Arguments.of("/admin//bootui", " must not contain consecutive slashes ('//'): '/admin//bootui'"),
                Arguments.of(
                        "/api/**",
                        " may contain only letters, digits, '-', '_', '.', '~', and '/' path separators: '/api/**'"),
                Arguments.of(
                        "  /admin/../bootui?foo=bar#section///  ",
                        " must not contain '.' or '..' path segments: '/admin/../bootui?foo=bar#section'"));
    }

    // --- DEFAULT_PATH constant ---

    @Test
    void defaultPathConstantIsBootui() {
        assertThat(BootUiPathNormalizer.DEFAULT_PATH).isEqualTo("/bootui");
    }
}
