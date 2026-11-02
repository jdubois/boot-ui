package io.github.jdubois.bootui.sample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class LangChain4jAlignmentTest {

    @Test
    void listenerCompileBaselineMatchesTheSampleRuntime() throws Exception {
        String compileVersion = System.getProperty("bootui.test.langchain4j.compile-version");
        assertNotNull(compileVersion, "Surefire must expose the listener's provided compile baseline");

        Properties runtime = new Properties();
        try (InputStream metadata = ChatModelListener.class
                .getClassLoader()
                .getResourceAsStream("META-INF/maven/dev.langchain4j/langchain4j-core/pom.properties")) {
            assertNotNull(metadata, "The sample runtime must contain LangChain4j core version metadata");
            runtime.load(metadata);
        }

        assertEquals(
                compileVersion,
                runtime.getProperty("version"),
                "The optional listener must compile against the core version supplied by the sample BOM");
    }
}
