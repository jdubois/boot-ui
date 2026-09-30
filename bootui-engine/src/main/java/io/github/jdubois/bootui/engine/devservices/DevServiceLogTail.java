package io.github.jdubois.bootui.engine.devservices;

import io.github.jdubois.bootui.core.dto.DevServiceLogReport;
import io.github.jdubois.bootui.engine.support.MessageExposure;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Shapes the bounded log tail of one development service for the Dev Services panel, applying the
 * {@link MessageExposure} rule that Exceptions and Log Tail share.
 *
 * <p>The rule is resolved on every request, so a live exposure change applies to the next read. Under
 * {@code METADATA_ONLY} the container is not asked for its logs at all. Otherwise the whole text is masked before it
 * is cut to {@code maxBytes}, so a cut can never separate a secret's value from the key that identifies it.</p>
 */
public final class DevServiceLogTail {

    private DevServiceLogTail() {}

    /**
     * @param id the service identifier echoed in the report
     * @param logs reads the service's current log text; not called when the policy omits log text
     * @param maxBytes the maximum number of UTF-8 bytes to return, keeping the newest output
     * @param exposure the live value-exposure policy
     */
    public static DevServiceLogReport report(String id, Supplier<String> logs, int maxBytes, ExposurePolicy exposure) {
        MessageExposure rule = MessageExposure.current(exposure);
        if (rule.omitsText()) {
            return new DevServiceLogReport(id, null, false, maxBytes, true);
        }
        String text = rule.apply(Objects.requireNonNullElse(logs.get(), ""));
        boolean truncated = text.getBytes(StandardCharsets.UTF_8).length > maxBytes;
        return new DevServiceLogReport(id, truncated ? tailByBytes(text, maxBytes) : text, truncated, maxBytes, false);
    }

    /** The newest {@code maxBytes} UTF-8 bytes of {@code text}, dropping a code point split by the cut. */
    static String tailByBytes(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        String tail = new String(bytes, bytes.length - maxBytes, maxBytes, StandardCharsets.UTF_8);
        int replacement = tail.indexOf('\uFFFD');
        while (replacement == 0 && tail.length() > 1) {
            tail = tail.substring(1);
            replacement = tail.indexOf('\uFFFD');
        }
        return tail;
    }
}
