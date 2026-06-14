package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.stream.BootUiChangeStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;

class NotifyingHttpExchangeRepositoryBeanPostProcessorTests {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BootUiChangeStream> providerOf(BootUiChangeStream changeStream) {
        ObjectProvider<BootUiChangeStream> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(changeStream);
        return provider;
    }

    @Test
    void wrapsHostProvidedRepository() {
        BootUiChangeStream changeStream = mock(BootUiChangeStream.class);
        HttpExchangeRepository hostRepository = mock(HttpExchangeRepository.class);
        NotifyingHttpExchangeRepositoryBeanPostProcessor processor =
                new NotifyingHttpExchangeRepositoryBeanPostProcessor(providerOf(changeStream));

        Object result = processor.postProcessAfterInitialization(hostRepository, "hostRepository");

        assertThat(result).isInstanceOf(NotifyingHttpExchangeRepository.class);
        assertThat(((NotifyingHttpExchangeRepository) result).delegate()).isSameAs(hostRepository);
    }

    @Test
    void doesNotDoubleWrapAlreadyDecoratedRepository() {
        BootUiChangeStream changeStream = mock(BootUiChangeStream.class);
        NotifyingHttpExchangeRepository decorated =
                new NotifyingHttpExchangeRepository(mock(HttpExchangeRepository.class), changeStream);
        NotifyingHttpExchangeRepositoryBeanPostProcessor processor =
                new NotifyingHttpExchangeRepositoryBeanPostProcessor(providerOf(changeStream));

        Object result = processor.postProcessAfterInitialization(decorated, "bootUiHttpExchangeRepository");

        assertThat(result).isSameAs(decorated);
    }

    @Test
    void leavesNonRepositoryBeansUntouched() {
        NotifyingHttpExchangeRepositoryBeanPostProcessor processor =
                new NotifyingHttpExchangeRepositoryBeanPostProcessor(providerOf(mock(BootUiChangeStream.class)));

        Object bean = new Object();
        assertThat(processor.postProcessAfterInitialization(bean, "someBean")).isSameAs(bean);
    }

    @Test
    void leavesRepositoryUnwrappedWhenNoChangeStreamIsAvailable() {
        HttpExchangeRepository hostRepository = mock(HttpExchangeRepository.class);
        NotifyingHttpExchangeRepositoryBeanPostProcessor processor =
                new NotifyingHttpExchangeRepositoryBeanPostProcessor(providerOf(null));

        assertThat(processor.postProcessAfterInitialization(hostRepository, "hostRepository"))
                .isSameAs(hostRepository);
    }
}
