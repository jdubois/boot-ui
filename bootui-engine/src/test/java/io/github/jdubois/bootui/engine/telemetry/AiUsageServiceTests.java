package io.github.jdubois.bootui.engine.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.AiChatDetailDto;
import io.github.jdubois.bootui.core.dto.SpanAttributeDto;
import io.github.jdubois.bootui.core.dto.SpanEventDto;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiUsageServiceTests {

    private static final String MASK = SecretMasker.MASKED_VALUE;

    private static final TelemetrySettings ENABLED = TelemetrySettings.of(true, true, 500, 500, 4096);

    private static final String PROMPT = "Use apiKey=sk-prompt-1 to summarize the order";

    private static final String COMPLETION = "Done, password=pw-completion-2 was accepted";

    private static final String INPUT_MESSAGES = "[{\"role\":\"user\",\"content\":\"token=tok-input-3\"}]";

    private static final String ERROR = "Provider rejected secret=sec-error-4";

    private static final List<String> SECRETS =
            List.of("sk-prompt-1", "pw-completion-2", "tok-input-3", "sec-error-4", "key-header-5");

    @Test
    void fullExposureReturnsChatSpanValuesVerbatim() {
        AiChatDetailDto detail = detail(policy(ValueExposure.FULL, true));

        assertThat(value(detail.attributes(), "gen_ai.prompt")).isEqualTo(PROMPT);
        assertThat(value(detail.attributes(), "gen_ai.completion")).isEqualTo(COMPLETION);
        assertThat(value(detail.attributes(), "gen_ai.input.messages")).isEqualTo(INPUT_MESSAGES);
        assertThat(value(detail.attributes(), "app.api_key")).isEqualTo("key-header-5");
        assertThat(value(exception(detail).attributes(), "exception.message")).isEqualTo(ERROR);
    }

    @Test
    void maskedExposureScrubsContentAndMasksSensitiveAttributes() {
        AiChatDetailDto detail = detail(policy(ValueExposure.MASKED, true));

        assertNoSecret(detail);
        assertThat(value(detail.attributes(), "gen_ai.prompt"))
                .isEqualTo("Use apiKey=" + MASK + " to summarize the order");
        assertThat(value(detail.attributes(), "gen_ai.completion"))
                .isEqualTo("Done, password=" + MASK + " was accepted");
        assertThat(value(detail.attributes(), "app.api_key")).isEqualTo(MASK);
        assertThat(value(exception(detail).attributes(), "exception.message"))
                .isEqualTo("Provider rejected secret=" + MASK);
        assertMetadata(detail);
    }

    @Test
    void metadataOnlyExposureOmitsContentButKeepsItsShape() {
        AiChatDetailDto detail = detail(policy(ValueExposure.METADATA_ONLY, true));

        assertNoSecret(detail);
        assertThat(value(detail.attributes(), "gen_ai.prompt")).isNull();
        assertThat(value(detail.attributes(), "gen_ai.completion")).isNull();
        assertThat(value(detail.attributes(), "gen_ai.input.messages")).isNull();
        assertThat(value(detail.attributes(), "app.api_key")).isEqualTo(MASK);
        assertThat(value(exception(detail).attributes(), "exception.message")).isNull();
        assertThat(value(exception(detail).attributes(), "exception.stacktrace"))
                .isNull();
        assertThat(detail.attributes())
                .extracting(SpanAttributeDto::key)
                .contains("gen_ai.prompt", "gen_ai.completion", "gen_ai.input.messages");
        assertMetadata(detail);
    }

    @Test
    void contentCaptureIsReportedWhateverTheExposureMode() {
        // Withholding the text must not make the panel claim content capture is off.
        AiChatDetailDto detail = detail(policy(ValueExposure.METADATA_ONLY, true));

        assertThat(detail.contentCaptured()).isTrue();
        assertThat(detail.contentBanner()).isNull();
    }

    @Test
    void everyReadAppliesTheLivePolicy() {
        MutablePolicy policy = new MutablePolicy(ValueExposure.FULL);
        AiUsageService service = service(policy);

        assertThat(value(service.chatDetail("chat-1").orElseThrow().attributes(), "gen_ai.prompt"))
                .isEqualTo(PROMPT);

        policy.exposure = ValueExposure.METADATA_ONLY;
        assertThat(value(service.chatDetail("chat-1").orElseThrow().attributes(), "gen_ai.prompt"))
                .isNull();

        policy.exposure = null;
        AiChatDetailDto unresolved = service.chatDetail("chat-1").orElseThrow();
        assertNoSecret(unresolved);
        assertThat(value(unresolved.attributes(), "gen_ai.prompt"))
                .isEqualTo("Use apiKey=" + MASK + " to summarize the order");
    }

    private static AiChatDetailDto detail(ExposurePolicy policy) {
        return service(policy).chatDetail("chat-1").orElseThrow();
    }

    private static AiUsageService service(ExposurePolicy policy) {
        TelemetryStore store = new TelemetryStore(ENABLED);
        store.add(chatSpan());
        return new AiUsageService(store, () -> new AiUsageSettings(true, 100, 60, true), () -> 0L, policy);
    }

    private static NormalizedSpan chatSpan() {
        Map<String, AttributeValue> attributes = new LinkedHashMap<>();
        attributes.put("gen_ai.operation.name", AttributeValue.ofString("chat"));
        attributes.put("gen_ai.system", AttributeValue.ofString("openai"));
        attributes.put("gen_ai.request.model", AttributeValue.ofString("gpt-test"));
        attributes.put("gen_ai.usage.input_tokens", AttributeValue.ofNumber(42L));
        attributes.put("gen_ai.prompt", AttributeValue.ofString(PROMPT));
        attributes.put("gen_ai.completion", AttributeValue.ofString(COMPLETION));
        attributes.put("gen_ai.input.messages", AttributeValue.ofString(INPUT_MESSAGES));
        attributes.put("app.api_key", AttributeValue.ofString("key-header-5"));
        Map<String, AttributeValue> exception = new LinkedHashMap<>();
        exception.put("exception.type", AttributeValue.ofString("java.lang.IllegalStateException"));
        exception.put("exception.message", AttributeValue.ofString(ERROR));
        exception.put(
                "exception.stacktrace",
                AttributeValue.ofString(
                        "java.lang.IllegalStateException: " + ERROR + "\n\tat com.example.Ai.call(Ai.java:1)"));
        return new NormalizedSpan(
                "trace-1",
                "chat-1",
                null,
                "chat gpt-test",
                "CLIENT",
                "sample",
                "test",
                1_000L,
                2_000L,
                "ERROR",
                ERROR,
                attributes,
                List.of(new NormalizedEvent("exception", 500L, exception)));
    }

    private static void assertNoSecret(AiChatDetailDto detail) {
        assertThat(detail.toString()).doesNotContain(SECRETS);
    }

    private static void assertMetadata(AiChatDetailDto detail) {
        assertThat(detail.summary().spanId()).isEqualTo("chat-1");
        assertThat(detail.summary().provider()).isEqualTo("openai");
        assertThat(detail.summary().requestModel()).isEqualTo("gpt-test");
        assertThat(detail.summary().inputTokens()).isEqualTo(42L);
        assertThat(value(detail.attributes(), "gen_ai.operation.name")).isEqualTo("chat");
        assertThat(value(detail.attributes(), "gen_ai.usage.input_tokens")).isEqualTo(42L);
        assertThat(value(exception(detail).attributes(), "exception.type"))
                .isEqualTo("java.lang.IllegalStateException");
    }

    private static SpanEventDto exception(AiChatDetailDto detail) {
        return detail.events().stream()
                .filter(event -> event.name().equals("exception"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing exception event"));
    }

    private static Object value(List<SpanAttributeDto> attributes, String key) {
        return attributes.stream()
                .filter(attribute -> attribute.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing attribute " + key))
                .value();
    }

    private static ExposurePolicy policy(ValueExposure exposure, boolean maskSecrets) {
        return TracesServiceTests.policy(exposure, maskSecrets);
    }

    private static final class MutablePolicy implements ExposurePolicy {

        private volatile ValueExposure exposure;

        private MutablePolicy(ValueExposure exposure) {
            this.exposure = exposure;
        }

        @Override
        public ValueExposure valueExposure() {
            return exposure;
        }

        @Override
        public boolean maskSecrets() {
            return true;
        }
    }
}
