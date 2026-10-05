package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.BootUiReactiveAutoConfiguration;
import io.github.jdubois.bootui.autoconfigure.BootUiReactiveSpringSecurityAutoConfiguration;
import io.github.jdubois.bootui.autoconfigure.architecture.ArchitectureController;
import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmController;
import io.github.jdubois.bootui.autoconfigure.web.GitHubController;
import io.github.jdubois.bootui.autoconfigure.web.HeapDumpController;
import io.github.jdubois.bootui.autoconfigure.web.ThreadDumpController;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerAdapter;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

class ReactiveBootUiHandlerAdapterTests {

    @Test
    void invokesBodyBearingActionOnConfiguredExecutorAfterDelayedDecode() {
        StaticApplicationContext applicationContext = new StaticApplicationContext();
        applicationContext.refresh();
        RequestMappingHandlerAdapter delegate = configuredDelegate(applicationContext);
        ExecutorService executor =
                Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "bootui-handler-test"));
        Scheduler bodyScheduler = Schedulers.newSingle("reactor-http-nio-test");
        ReactiveBootUiHandlerAdapter adapter =
                new ReactiveBootUiHandlerAdapter(provider(delegate), applicationContext, executor);
        AtomicReference<String> handlerThread = new AtomicReference<>();
        HandlerMethod handler = handlerMethod(new BodyActionController(handlerThread));
        var body = reactor.core.publisher.Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(
                        "payload".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .delayElements(Duration.ofMillis(10), bodyScheduler);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/bootui/api/action")
                .contentType(MediaType.TEXT_PLAIN)
                .body(body));

        try {
            adapter.handle(exchange, handler).block(Duration.ofSeconds(5));
        } finally {
            executor.shutdownNow();
            bodyScheduler.dispose();
            applicationContext.close();
        }

        assertThat(handlerThread.get()).startsWith("bootui-handler-test");
    }

    @Test
    void supportsBootUiApiControllersButNotShellOrHostControllers() {
        ReactiveBootUiHandlerAdapter adapter = new ReactiveBootUiHandlerAdapter(
                provider(mock(RequestMappingHandlerAdapter.class)), new StaticApplicationContext());

        assertThat(adapter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(adapter.supports(handlerMethod(new DefaultApiController()))).isTrue();
        assertThat(adapter.supports(handlerMethod(new CustomApiController()))).isTrue();
        assertThat(adapter.supports(handlerMethod(new ShellController()))).isFalse();
        assertThat(adapter.supports(handlerMethod(new HostApplicationController())))
                .isFalse();
        assertThat(adapter.supports("not a handler method")).isFalse();
    }

    @Test
    void coversEveryApiControllerImportedByTheReactiveAutoConfigurations() {
        List<Class<?>> primaryControllers = importedControllers(BootUiReactiveAutoConfiguration.class);
        List<Class<?>> securityControllers = importedControllers(BootUiReactiveSpringSecurityAutoConfiguration.class);

        assertThat(primaryControllers)
                .contains(
                        GitHubController.class,
                        ArchitectureController.class,
                        HeapDumpController.class,
                        ThreadDumpController.class,
                        GraalVmController.class)
                .allMatch(ReactiveBootUiHandlerAdapter::isBootUiApi);
        assertThat(securityControllers).allMatch(ReactiveBootUiHandlerAdapter::isBootUiApi);
        assertThat(ReactiveBootUiHandlerAdapter.isBootUiApi(ReactiveBootUiIndexController.class))
                .isFalse();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(value);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static RequestMappingHandlerAdapter configuredDelegate(StaticApplicationContext applicationContext) {
        RequestMappingHandlerAdapter delegate = new RequestMappingHandlerAdapter();
        delegate.setMessageReaders(ServerCodecConfigurer.create().getReaders());
        delegate.setReactiveAdapterRegistry(ReactiveAdapterRegistry.getSharedInstance());
        delegate.setApplicationContext(applicationContext);
        try {
            delegate.afterPropertiesSet();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return delegate;
    }

    private static HandlerMethod handlerMethod(Object controller) {
        Method method = Arrays.stream(controller.getClass().getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("handle"))
                .findFirst()
                .orElseThrow();
        return new HandlerMethod(controller, method);
    }

    private static List<Class<?>> importedControllers(Class<?> configurationClass) {
        Import imports = configurationClass.getAnnotation(Import.class);
        return Arrays.stream(imports.value())
                .filter(type -> AnnotatedElementUtils.hasAnnotation(type, RestController.class))
                .toList();
    }

    @RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/overview")
    private static final class DefaultApiController {

        @GetMapping
        void handle() {}
    }

    @RequestMapping("${bootui.api-path:/bootui/api}/security")
    private static final class CustomApiController {

        @GetMapping
        void handle() {}
    }

    @RequestMapping("${bootui.path:/bootui}")
    private static final class ShellController {

        @GetMapping
        void handle() {}
    }

    @RequestMapping("/api/orders")
    private static final class HostApplicationController {

        @GetMapping
        void handle() {}
    }

    @RequestMapping("${bootui.api-path:/bootui/api}/action")
    private static final class BodyActionController {

        private final AtomicReference<String> handlerThread;

        private BodyActionController(AtomicReference<String> handlerThread) {
            this.handlerThread = handlerThread;
        }

        @PostMapping
        String handle(@RequestBody String body) {
            handlerThread.set(Thread.currentThread().getName());
            return body;
        }
    }
}
