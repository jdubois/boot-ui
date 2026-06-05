package io.github.jdubois.bootui.autoconfigure.copilotfix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CopilotFixDetectorTests {

    @Test
    void sdkIsNotPresentOnTheTestClasspath() {
        assertThat(CopilotFixDetector.isSdkPresent()).isFalse();
    }

    @Test
    void reportsAbsentWhenNoMarkerResolves() {
        ClassLoader loader = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                throw new ClassNotFoundException(name);
            }
        };
        assertThat(CopilotFixDetector.isSdkPresent(loader)).isFalse();
    }
}
