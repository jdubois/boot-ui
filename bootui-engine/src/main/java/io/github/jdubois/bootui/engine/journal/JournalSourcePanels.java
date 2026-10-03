package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The panel that owns each journal source's evidence ({@code docs/PLAN-v2.md} §8, source-panel policy). One mapping
 * serves Live Activity, request profiles, and Runtime Insights, so a source cannot be gated in one surface and open in
 * another.
 *
 * <p>The switch is exhaustive on purpose: a new {@link JournalSource} fails compilation here until its owning panel, or
 * its deliberate absence of one, is declared.
 */
public final class JournalSourcePanels {

    private JournalSourcePanels() {}

    /**
     * The panels that own {@code source}'s evidence, empty when no panel does.
     *
     * <p>{@code APP_EVENT}, {@code LIFECYCLE}, {@code GC}, and {@code RESOURCES} own no panel by design: they record
     * the application's own lifecycle, its garbage collections, and its process resources, none of which a panel
     * publishes as its own evidence.
     */
    public static List<String> panelsOf(JournalSource source) {
        return switch (source) {
            case HTTP -> List.of(BootUiPanels.HTTP_EXCHANGES);
            case SQL, CONNECTION -> List.of(BootUiPanels.SQL_TRACE);
            case TRANSACTION -> List.of(BootUiPanels.TRANSACTIONS);
            case EXCEPTION -> List.of(BootUiPanels.EXCEPTIONS);
            case SECURITY, AUTHORIZATION -> List.of(BootUiPanels.SECURITY_LOGS);
            case REST_CLIENT -> List.of(BootUiPanels.REST_CLIENT_TRACE);
            case CACHE -> List.of(BootUiPanels.CACHE);
            case MESSAGING -> List.of(BootUiPanels.KAFKA, BootUiPanels.RABBITMQ, BootUiPanels.JMS);
            case SCHEDULED -> List.of(BootUiPanels.SCHEDULED);
            case LOG -> List.of(BootUiPanels.LOG_TAIL);
            case MAIL -> List.of(BootUiPanels.EMAIL);
            case FAULT_TOLERANCE -> List.of(BootUiPanels.FAULT_TOLERANCE);
            case AI -> List.of(BootUiPanels.AI);
            case WEBSOCKET -> List.of(BootUiPanels.WEBSOCKETS);
            case ORM -> List.of(BootUiPanels.HIBERNATE);
            case AGENT_EXECUTORS -> List.of(BootUiPanels.JAVA_AGENT);
            case APP_EVENT, LIFECYCLE, GC, RESOURCES -> List.of();
        };
    }

    /**
     * The panel that owns one event's evidence, or {@code null} when none does. A messaging event is owned by its
     * broker's panel, so disabling one broker's panel leaves the others' rows in place.
     */
    public static String panelOf(RuntimeEvent event) {
        if (event.source() == JournalSource.MESSAGING) {
            String broker = event.payload() instanceof MessagingPayload message ? String.valueOf(message.broker()) : "";
            return switch (broker) {
                case "jms" -> BootUiPanels.JMS;
                case "rabbitmq" -> BootUiPanels.RABBITMQ;
                default -> BootUiPanels.KAFKA;
            };
        }
        List<String> panels = panelsOf(event.source());
        return panels.isEmpty() ? null : panels.get(0);
    }

    /** Every panel that owns at least one source, in source order, each listed once. */
    public static List<String> owningPanels() {
        LinkedHashSet<String> panels = new LinkedHashSet<>();
        for (JournalSource source : JournalSource.values()) {
            panels.addAll(panelsOf(source));
        }
        return List.copyOf(new ArrayList<>(panels));
    }
}
