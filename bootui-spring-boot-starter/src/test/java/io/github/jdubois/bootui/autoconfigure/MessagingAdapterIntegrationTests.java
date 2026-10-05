package io.github.jdubois.bootui.autoconfigure;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.jms.Connection;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.util.Map;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.AbstractMessageListenerContainer;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Broker-free integration coverage for the shared Spring messaging adapters.
 *
 * <p>Unlike the focused processor/controller unit tests, these tests boot each real auto-configuration,
 * let Spring post-process application-owned templates and listener factories, drive the framework
 * callbacks with mocked transports, and read the resulting shared recorder state through the real HTTP
 * endpoints. No broker connection, socket, background listener, Docker service, or timing wait is used.
 */
class MessagingAdapterIntegrationTests {

    private static final String RAW_JMS_MESSAGE_ID = "ID:provider-secret";
    private static final String RAW_RABBIT_CORRELATION_ID = "customer-secret";
    private static final String SENSITIVE_PAYLOAD = "payload-secret";
    private static final String SENSITIVE_HEADER = "header-secret";

    @Test
    void servletAdapterWiresJmsAndRabbitCaptureThroughTheSharedApi() {
        servletRunner().run(context -> {
            assertThat(context).hasNotFailed();
            exerciseMessagingCallbacks(context);

            MockMvc mvc = MockMvcBuilders.webAppContextSetup(
                            (WebApplicationContext) context.getSourceApplicationContext())
                    .build();
            assertMvcReports(mvc);
        });
    }

    @Test
    void reactiveAdapterWiresJmsAndRabbitCaptureThroughTheSharedApi() {
        reactiveRunner().run(context -> {
            assertThat(context).hasNotFailed();
            exerciseMessagingCallbacks(context);

            WebTestClient client = WebTestClient.bindToApplicationContext(context.getSourceApplicationContext())
                    .configureClient()
                    .defaultHeader(
                            "Authorization",
                            "Bearer "
                                    + context.getBean(
                                                    io.github.jdubois.bootui.engine.safety.ApiTokenAuthenticator.class)
                                            .token())
                    .build();
            assertWebFluxReports(client);
        });
    }

    private static void exerciseMessagingCallbacks(ApplicationContext context) {
        try {
            JmsTemplate jmsTemplate = context.getBean(JmsTemplate.class);
            assertThat(jmsTemplate.getClass()).isNotEqualTo(JmsTemplate.class);
            jmsTemplate.send("orders?token=raw-secret", session -> outgoingJmsMessage());

            DefaultJmsListenerContainerFactory jmsFactory = context.getBean(DefaultJmsListenerContainerFactory.class);
            SimpleJmsListenerEndpoint endpoint = new SimpleJmsListenerEndpoint();
            endpoint.setId("orders-listener");
            endpoint.setDestination("orders");
            endpoint.setSubscription("orders-sub?token=raw-secret");
            jakarta.jms.MessageListener listener =
                    message -> assertThat(message).isNotNull();
            endpoint.setMessageListener(listener);
            AbstractMessageListenerContainer container = jmsFactory.createListenerContainer(endpoint);
            assertThat(container.getMessageListener()).isNotSameAs(listener);
            ((jakarta.jms.MessageListener) container.getMessageListener()).onMessage(incomingJmsMessage());

            RabbitTemplate rabbitTemplate = context.getBean(RabbitTemplate.class);
            assertThat(rabbitTemplate.getBeforePublishPostProcessors()).isNotEmpty();
            Message published = rabbitMessage("orders", "created", null);
            for (MessagePostProcessor postProcessor : rabbitTemplate.getBeforePublishPostProcessors()) {
                postProcessor.postProcessMessage(published, null, "orders", "created");
            }

            SimpleRabbitListenerContainerFactory rabbitFactory =
                    context.getBean(SimpleRabbitListenerContainerFactory.class);
            assertThat(rabbitFactory.getAdviceChain()).isNotEmpty();
            MethodInvocation delivery = mock(MethodInvocation.class);
            when(delivery.getArguments()).thenReturn(new Object[] {rabbitMessage("orders", "created", "fulfillment")});
            when(delivery.proceed()).thenReturn(null);
            ((MethodInterceptor) rabbitFactory.getAdviceChain()[0]).invoke(delivery);
        } catch (Throwable ex) {
            throw new AssertionError("Could not drive broker-free messaging callbacks", ex);
        }
    }

    private static void assertMvcReports(MockMvc mvc) {
        try {
            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.panels[?(@.id=='jms')].available").value(true))
                    .andExpect(
                            jsonPath("$.panels[?(@.id=='rabbitmq')].available").value(true));

            MvcResult jmsResult = mvc.perform(get("/bootui/api/jms"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(true))
                    .andExpect(jsonPath("$.total").value(2))
                    .andExpect(jsonPath("$.totalCaptured").value(2))
                    .andExpect(jsonPath("$.messages[0].direction").value("CONSUME"))
                    .andExpect(jsonPath("$.messages[0].destination").value("inbound?token=******"))
                    .andExpect(jsonPath("$.messages[0].messageId").isNotEmpty())
                    .andExpect(jsonPath("$.messages[0].subscriptionName").value("orders-sub?token=******"))
                    .andExpect(jsonPath("$.messages[1].direction").value("PRODUCE"))
                    .andExpect(jsonPath("$.messages[1].destination").value("orders?token=******"))
                    .andExpect(jsonPath("$.messages[1].messageId").isNotEmpty())
                    .andReturn();
            assertSensitiveValuesAbsent(jmsResult.getResponse().getContentAsString());

            MvcResult rabbitResult = mvc.perform(get("/bootui/api/rabbitmq"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(true))
                    .andExpect(jsonPath("$.total").value(2))
                    .andExpect(jsonPath("$.totalCaptured").value(2))
                    .andExpect(jsonPath("$.messages[0].direction").value("CONSUME"))
                    .andExpect(jsonPath("$.messages[0].queue").value("fulfillment"))
                    .andExpect(jsonPath("$.messages[0].correlationId").isNotEmpty())
                    .andExpect(jsonPath("$.messages[1].direction").value("PUBLISH"))
                    .andExpect(jsonPath("$.messages[1].correlationId").isNotEmpty())
                    .andReturn();
            assertSensitiveValuesAbsent(rabbitResult.getResponse().getContentAsString());

            mvc.perform(delete("/bootui/api/jms")).andExpect(status().isNoContent());
            mvc.perform(delete("/bootui/api/rabbitmq")).andExpect(status().isNoContent());
            mvc.perform(get("/bootui/api/jms"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0))
                    .andExpect(jsonPath("$.totalCaptured").value(2));
            mvc.perform(get("/bootui/api/rabbitmq"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0))
                    .andExpect(jsonPath("$.totalCaptured").value(2));
        } catch (Exception ex) {
            throw new AssertionError("Could not verify servlet messaging reports", ex);
        }
    }

    private static void assertWebFluxReports(WebTestClient client) {
        client.get()
                .uri("/bootui/api/panels")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.panels[?(@.id=='jms')].available")
                .isEqualTo(true)
                .jsonPath("$.panels[?(@.id=='rabbitmq')].available")
                .isEqualTo(true);

        byte[] jmsBody = client.get()
                .uri("/bootui/api/jms")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.available")
                .isEqualTo(true)
                .jsonPath("$.total")
                .isEqualTo(2)
                .jsonPath("$.totalCaptured")
                .isEqualTo(2)
                .jsonPath("$.messages[0].direction")
                .isEqualTo("CONSUME")
                .jsonPath("$.messages[0].destination")
                .isEqualTo("inbound?token=******")
                .jsonPath("$.messages[0].messageId")
                .isNotEmpty()
                .jsonPath("$.messages[0].subscriptionName")
                .isEqualTo("orders-sub?token=******")
                .jsonPath("$.messages[1].direction")
                .isEqualTo("PRODUCE")
                .jsonPath("$.messages[1].messageId")
                .isNotEmpty()
                .returnResult()
                .getResponseBody();
        assertSensitiveValuesAbsent(new String(jmsBody, UTF_8));

        byte[] rabbitBody = client.get()
                .uri("/bootui/api/rabbitmq")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.available")
                .isEqualTo(true)
                .jsonPath("$.total")
                .isEqualTo(2)
                .jsonPath("$.totalCaptured")
                .isEqualTo(2)
                .jsonPath("$.messages[0].direction")
                .isEqualTo("CONSUME")
                .jsonPath("$.messages[0].queue")
                .isEqualTo("fulfillment")
                .jsonPath("$.messages[0].correlationId")
                .isNotEmpty()
                .jsonPath("$.messages[1].direction")
                .isEqualTo("PUBLISH")
                .jsonPath("$.messages[1].correlationId")
                .isNotEmpty()
                .returnResult()
                .getResponseBody();
        assertSensitiveValuesAbsent(new String(rabbitBody, UTF_8));

        client.delete().uri("/bootui/api/jms").exchange().expectStatus().isNoContent();
        client.delete().uri("/bootui/api/rabbitmq").exchange().expectStatus().isNoContent();
        client.get()
                .uri("/bootui/api/jms")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(0)
                .jsonPath("$.totalCaptured")
                .isEqualTo(2);
        client.get()
                .uri("/bootui/api/rabbitmq")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(0)
                .jsonPath("$.totalCaptured")
                .isEqualTo(2);
    }

    private static void assertSensitiveValuesAbsent(String json) {
        assertThat(json)
                .doesNotContain(RAW_JMS_MESSAGE_ID)
                .doesNotContain(RAW_RABBIT_CORRELATION_ID)
                .doesNotContain(SENSITIVE_PAYLOAD)
                .doesNotContain(SENSITIVE_HEADER);
    }

    private static TextMessage outgoingJmsMessage() throws JMSException {
        TextMessage message = mock(TextMessage.class);
        when(message.getJMSMessageID()).thenReturn(RAW_JMS_MESSAGE_ID);
        when(message.getText()).thenReturn(SENSITIVE_PAYLOAD);
        when(message.getStringProperty("secret")).thenReturn(SENSITIVE_HEADER);
        return message;
    }

    private static jakarta.jms.Message incomingJmsMessage() throws JMSException {
        jakarta.jms.Message message = mock(jakarta.jms.Message.class);
        Queue queue = mock(Queue.class);
        when(queue.getQueueName()).thenReturn("inbound?token=raw-secret");
        when(message.getJMSDestination()).thenReturn(queue);
        when(message.getJMSMessageID()).thenReturn(RAW_JMS_MESSAGE_ID);
        when(message.getStringProperty("secret")).thenReturn(SENSITIVE_HEADER);
        return message;
    }

    private static Message rabbitMessage(String exchange, String routingKey, String queue) {
        MessageProperties properties = new MessageProperties();
        properties.setReceivedExchange(exchange);
        properties.setReceivedRoutingKey(routingKey);
        properties.setConsumerQueue(queue);
        properties.setCorrelationId(RAW_RABBIT_CORRELATION_ID);
        properties.setHeaders(Map.of("secret", SENSITIVE_HEADER));
        return new Message(SENSITIVE_PAYLOAD.getBytes(UTF_8), properties);
    }

    private static WebApplicationContextRunner servletRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DispatcherServletAutoConfiguration.class,
                        WebMvcAutoConfiguration.class,
                        BootUiAutoConfiguration.class))
                .withUserConfiguration(MessagingFixture.class)
                .withPropertyValues(
                        "bootui.enabled=ON",
                        "bootui.allow-non-localhost=true",
                        "bootui.jms.capture-message-id=true",
                        "bootui.jms.max-message-id-length=16",
                        "bootui.rabbitmq.capture-correlation-id=true",
                        "bootui.rabbitmq.max-correlation-id-length=16");
    }

    private static ReactiveWebApplicationContextRunner reactiveRunner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        HttpHandlerAutoConfiguration.class,
                        WebFluxAutoConfiguration.class,
                        BootUiReactiveAutoConfiguration.class))
                .withUserConfiguration(MessagingFixture.class)
                .withPropertyValues(
                        "bootui.enabled=ON",
                        "bootui.allow-non-localhost=true",
                        "bootui.jms.capture-message-id=true",
                        "bootui.jms.max-message-id-length=16",
                        "bootui.rabbitmq.capture-correlation-id=true",
                        "bootui.rabbitmq.max-correlation-id-length=16");
    }

    @Configuration(proxyBeanMethods = false)
    static class MessagingFixture {

        @Bean
        jakarta.jms.ConnectionFactory jmsConnectionFactory() throws Exception {
            jakarta.jms.ConnectionFactory factory = mock(jakarta.jms.ConnectionFactory.class);
            Connection connection = mock(Connection.class);
            Session session = mock(Session.class);
            MessageProducer producer = mock(MessageProducer.class);
            when(factory.createConnection()).thenReturn(connection);
            when(connection.createSession(false, Session.AUTO_ACKNOWLEDGE)).thenReturn(session);
            when(session.createQueue(anyString())).thenAnswer(invocation -> {
                Queue queue = mock(Queue.class);
                when(queue.getQueueName()).thenReturn(invocation.getArgument(0));
                return queue;
            });
            when(session.createProducer(any(Destination.class))).thenReturn(producer);
            return factory;
        }

        @Bean
        JmsTemplate jmsTemplate(jakarta.jms.ConnectionFactory connectionFactory) {
            return new JmsTemplate(connectionFactory);
        }

        @Bean
        DefaultJmsListenerContainerFactory jmsListenerContainerFactory(
                jakarta.jms.ConnectionFactory connectionFactory) {
            DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
            factory.setConnectionFactory(connectionFactory);
            return factory;
        }

        @Bean
        org.springframework.amqp.rabbit.connection.ConnectionFactory rabbitConnectionFactory() {
            return mock(org.springframework.amqp.rabbit.connection.ConnectionFactory.class);
        }

        @Bean
        RabbitTemplate rabbitTemplate(org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory) {
            return new RabbitTemplate(connectionFactory);
        }

        @Bean
        SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
                org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory) {
            SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
            factory.setConnectionFactory(connectionFactory);
            return factory;
        }
    }
}
