package io.github.jdubois.bootui.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The build filters {@code bootui-version.properties}, so the version reported at run time is the project's own. */
class BootUiInfoTests {

    private static final Pattern PARENT_VERSION =
            Pattern.compile("<artifactId>bootui-parent</artifactId>\\s*<version>([^<]+)</version>");

    @Test
    void versionIsTheFilteredProjectVersion() throws IOException {
        Matcher matcher = PARENT_VERSION.matcher(Files.readString(Path.of("pom.xml")));
        assertThat(matcher.find())
                .as("bootui-engine/pom.xml declares its parent version")
                .isTrue();

        assertThat(BootUiInfo.VERSION).isEqualTo(matcher.group(1)).doesNotContain("${");
    }
}
