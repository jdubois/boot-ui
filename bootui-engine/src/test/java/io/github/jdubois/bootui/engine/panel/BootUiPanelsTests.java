package io.github.jdubois.bootui.engine.panel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.jdubois.bootui.engine.panel.BootUiPanels.Panel;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BootUiPanelsTests {

    @Test
    void catalogContainsEveryPublishedPanelIdExactlyOnce() {
        Set<String> publishedIds = Arrays.stream(BootUiPanels.class.getFields())
                .filter(field -> field.getType() == String.class)
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .map(BootUiPanelsTests::readString)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(BootUiPanels.all()).extracting(Panel::id).doesNotHaveDuplicates();
        assertThat(BootUiPanels.ids()).containsExactlyInAnyOrderElementsOf(publishedIds);
    }

    @Test
    void everyApiPrefixResolvesToItsOwningPanel() {
        for (Panel panel : BootUiPanels.all()) {
            for (String apiPrefix : panel.apiPrefixes()) {
                assertThat(BootUiPanels.byApiPath(apiPrefix))
                        .as("exact API prefix lookup for %s", panel.id())
                        .contains(panel);
                assertThat(BootUiPanels.byApiPath(apiPrefix + "/sample"))
                        .as("nested API prefix lookup for %s", panel.id())
                        .contains(panel);
            }
        }
    }

    @Test
    void catalogRejectsInvalidPanelMetadata() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Panel("", "Title", false, "/sample"))
                .withMessageContaining("id");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Panel("sample", "", false, "/sample"))
                .withMessageContaining("title");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Panel("sample", "Sample", true, List.of()))
                .withMessageContaining("Action-capable");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Panel("sample", "Sample", false, "sample"))
                .withMessageContaining("invalid API prefix");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Panel("sample", "Sample", false, List.of("/sample", "/sample")))
                .withMessageContaining("duplicate API prefixes");
    }

    private static String readString(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Unable to read public panel id constant " + field.getName(), ex);
        }
    }
}
