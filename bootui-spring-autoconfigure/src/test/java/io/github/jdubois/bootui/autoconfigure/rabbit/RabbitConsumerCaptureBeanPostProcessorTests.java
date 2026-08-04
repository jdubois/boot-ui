package io.github.jdubois.bootui.autoconfigure.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.beans.factory.ObjectProvider;

class RabbitConsumerCaptureBeanPostProcessorTests {

    @Test
    void prependsCaptureAdviceWithoutReplacingApplicationAdvice() throws Throwable {
        RabbitActivityRecorder recorder = new RabbitActivityRecorder(true, true, 10, 16);
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        MethodInterceptor existing = mock(MethodInterceptor.class);
        factory.setAdviceChain(existing);
        RabbitConsumerCaptureBeanPostProcessor processor =
                new RabbitConsumerCaptureBeanPostProcessor(provider(recorder));
        MessageProperties properties = new MessageProperties();
        properties.setReceivedExchange("orders");
        properties.setReceivedRoutingKey("created");
        properties.setConsumerQueue("workers");
        properties.setCorrelationId("customer-123");
        Message message = new Message("sensitive payload".getBytes(), properties);
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getArguments()).thenReturn(new Object[] {mock(com.rabbitmq.client.Channel.class), message});
        when(invocation.proceed()).thenReturn("result");

        processor.postProcessAfterInitialization(factory, "rabbitListenerContainerFactory");
        Advice[] advice = factory.getAdviceChain();
        Object result = ((MethodInterceptor) advice[0]).invoke(invocation);

        assertThat(result).isEqualTo("result");
        assertThat(advice).hasSize(2);
        assertThat(advice[1]).isSameAs(existing);
        verify(invocation).proceed();
        assertThat(recorder.recent()).singleElement().satisfies(captured -> {
            assertThat(captured.direction()).isEqualTo(RabbitActivityRecorder.Direction.CONSUME);
            assertThat(captured.queue()).isEqualTo("workers");
            assertThat(captured.success()).isTrue();
            assertThat(captured.toString()).doesNotContain("sensitive payload");
        });
    }

    @Test
    void capturesEveryMessageInABatchInvocation() throws Throwable {
        RabbitActivityRecorder recorder = new RabbitActivityRecorder(true, false, 10, 16);
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        RabbitConsumerCaptureBeanPostProcessor processor =
                new RabbitConsumerCaptureBeanPostProcessor(provider(recorder));
        Message first = message("orders", "created");
        Message second = message("orders", "updated");
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getArguments())
                .thenReturn(new Object[] {mock(com.rabbitmq.client.Channel.class), java.util.List.of(first, second)});
        when(invocation.proceed()).thenReturn(null);

        processor.postProcessAfterInitialization(factory, "rabbitListenerContainerFactory");
        ((MethodInterceptor) factory.getAdviceChain()[0]).invoke(invocation);

        assertThat(recorder.recent())
                .extracting(RabbitActivityRecorder.CapturedMessage::routingKey)
                .containsExactly("updated", "created");
    }

    @Test
    void recordsGenericFailureWithoutLeakingExceptionText() throws Throwable {
        RabbitActivityRecorder recorder = new RabbitActivityRecorder(true, false, 10, 16);
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        RabbitConsumerCaptureBeanPostProcessor processor =
                new RabbitConsumerCaptureBeanPostProcessor(provider(recorder));
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getArguments()).thenReturn(new Object[] {new Message(new byte[0], new MessageProperties())});
        when(invocation.proceed()).thenThrow(new IllegalStateException("password=secret"));

        processor.postProcessAfterInitialization(factory, "rabbitListenerContainerFactory");
        try {
            ((MethodInterceptor) factory.getAdviceChain()[0]).invoke(invocation);
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("password=secret");
        }

        assertThat(recorder.recent()).singleElement().satisfies(captured -> {
            assertThat(captured.success()).isFalse();
            assertThat(captured.errorMessage()).isEqualTo("Message processing failed");
        });
    }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static Message message(String exchange, String routingKey) {
        MessageProperties properties = new MessageProperties();
        properties.setReceivedExchange(exchange);
        properties.setReceivedRoutingKey(routingKey);
        return new Message(new byte[0], properties);
    }
}
