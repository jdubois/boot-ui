package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JournalSourcePanelsTests {

    /**
     * The sources no panel owns, which the application's own lifecycle, its collections, and its process resources
     * record rather than a panel ({@code docs/PLAN-v2.md} §8).
     */
    private static final Set<JournalSource> UNOWNED =
            EnumSet.of(JournalSource.APP_EVENT, JournalSource.LIFECYCLE, JournalSource.GC, JournalSource.RESOURCES);

    @Test
    void everySourceNamesPanelsThatExistAndOnlyTheDeclaredFourNameNone() {
        for (JournalSource source : JournalSource.values()) {
            List<String> panels = JournalSourcePanels.panelsOf(source);
            assertThat(panels)
                    .as("%s names panels BootUI publishes", source)
                    .allSatisfy(panel -> assertThat(BootUiPanels.ids()).contains(panel));
            assertThat(panels.isEmpty())
                    .as("%s owns a panel unless it is one of the four declared exceptions", source)
                    .isEqualTo(UNOWNED.contains(source));
        }
    }

    @Test
    void theSourcesV2AddedAreOwnedByThePanelThatPublishesThem() {
        assertThat(JournalSourcePanels.panelsOf(JournalSource.AUTHORIZATION))
                .containsExactly(BootUiPanels.SECURITY_LOGS);
        assertThat(JournalSourcePanels.panelsOf(JournalSource.ORM)).containsExactly(BootUiPanels.HIBERNATE);
        assertThat(JournalSourcePanels.panelsOf(JournalSource.WEBSOCKET)).containsExactly(BootUiPanels.WEBSOCKETS);
        assertThat(JournalSourcePanels.panelsOf(JournalSource.AGENT_EXECUTORS))
                .containsExactly(BootUiPanels.JAVA_AGENT);
        assertThat(JournalSourcePanels.panelsOf(JournalSource.CACHE)).containsExactly(BootUiPanels.CACHE);
        assertThat(JournalSourcePanels.panelsOf(JournalSource.SECURITY)).containsExactly(BootUiPanels.SECURITY_LOGS);
        assertThat(JournalSourcePanels.panelsOf(JournalSource.CONNECTION)).containsExactly(BootUiPanels.SQL_TRACE);
        assertThat(JournalSourcePanels.panelsOf(JournalSource.MESSAGING))
                .containsExactly(BootUiPanels.KAFKA, BootUiPanels.RABBITMQ, BootUiPanels.JMS);
    }

    @Test
    void aMessagingEventIsOwnedByItsBrokersPanelAndAnUnknownBrokerReadsAsKafka() {
        assertThat(JournalSourcePanels.panelOf(messaging("jms"))).isEqualTo(BootUiPanels.JMS);
        assertThat(JournalSourcePanels.panelOf(messaging("rabbitmq"))).isEqualTo(BootUiPanels.RABBITMQ);
        assertThat(JournalSourcePanels.panelOf(messaging("kafka"))).isEqualTo(BootUiPanels.KAFKA);
        assertThat(JournalSourcePanels.panelOf(messaging(null))).isEqualTo(BootUiPanels.KAFKA);
    }

    @Test
    void anEventOfAnUnownedSourceNamesNoPanelAndTheOwningPanelsAreListedOnce() {
        RuntimeEvent event = RuntimeEvent.of(
                JournalSource.APP_EVENT,
                0,
                0,
                CorrelationContext.NONE,
                "main",
                null,
                false,
                new AppEventPayload("PUBLISH", "OrderPlaced", null, null, "OK", null, 1, 0));

        assertThat(JournalSourcePanels.panelOf(event)).isNull();
        assertThat(JournalSourcePanels.owningPanels())
                .doesNotHaveDuplicates()
                .contains(BootUiPanels.SQL_TRACE, BootUiPanels.SECURITY_LOGS, BootUiPanels.JMS);
    }

    private static RuntimeEvent messaging(String broker) {
        return RuntimeEvent.of(
                JournalSource.MESSAGING,
                0,
                0,
                CorrelationContext.NONE,
                "main",
                null,
                false,
                new MessagingPayload(broker, true, "orders", false, null));
    }

    @Test
    void everyStoredRowTypeIsGatedByAPanelBootUiPublishesOrIsDeclaredPanelFree() throws IllegalAccessException {
        for (Field field : JournalActivityFeed.class.getFields()) {
            if (!field.getName().startsWith("TYPE_") || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String type = (String) field.get(null);
            ActivityEntryDto row = row(type, null);
            boolean panelFree =
                    type.equals(JournalActivityFeed.TYPE_MARKER) || type.equals(JournalActivityFeed.TYPE_APP_EVENT);
            assertThat(JournalSourcePanels.isReadable(row, panel -> true))
                    .as("%s is readable while every panel is enabled", type)
                    .isTrue();
            assertThat(JournalSourcePanels.isReadable(row, panel -> false))
                    .as("%s is hidden once its panel is disabled, unless it owns none", type)
                    .isEqualTo(panelFree);
            if (!panelFree) {
                assertThat(BootUiPanels.ids()).contains(JournalSourcePanels.panelOfRow(row));
            }
        }
    }

    @Test
    void aStoredMessagingRowIsGatedByItsBrokersPanelAndAnUnknownRowFailsClosed() {
        assertThat(JournalSourcePanels.panelOfRow(row(JournalActivityFeed.TYPE_MESSAGING, "jms")))
                .isEqualTo(BootUiPanels.JMS);
        assertThat(JournalSourcePanels.panelOfRow(row(JournalActivityFeed.TYPE_MESSAGING, "rabbitmq")))
                .isEqualTo(BootUiPanels.RABBITMQ);
        assertThat(JournalSourcePanels.panelOfRow(row(JournalActivityFeed.TYPE_MESSAGING, "kafka")))
                .isEqualTo(BootUiPanels.KAFKA);
        assertThat(JournalSourcePanels.isReadable(
                        row(JournalActivityFeed.TYPE_MESSAGING, "jms"), panel -> !panel.equals(BootUiPanels.JMS)))
                .isFalse();
        assertThat(JournalSourcePanels.isReadable(row("FROM_A_NEWER_VERSION", null), panel -> true))
                .isFalse();
        assertThat(JournalSourcePanels.isReadable(null, panel -> true)).isFalse();
    }

    private static ActivityEntryDto row(String type, String detail) {
        return new ActivityEntryDto(
                "1", type, 0L, "OK", "summary", detail, null, null, null, null, null, null, false, null, null, false);
    }
}
