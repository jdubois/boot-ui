package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.spi.CorrelationContext;
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
}
