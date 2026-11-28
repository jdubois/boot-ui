package io.github.jdubois.bootui.quarkus4.appevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.quarkus4.nativeconsole.BootUiNativeActivation;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

class QuarkusNativeObserverCaptureTest {

    @Test
    void denialProceedsWithoutReadingTheObservedEventOrResolvingTheJournal() throws Exception {
        InvocationContext invocation = mock(InvocationContext.class);
        Object result = new Object();
        when(invocation.proceed()).thenReturn(result);
        try (var activation = mockStatic(BootUiNativeActivation.class)) {
            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(true);
            assertThat(new QuarkusObserverInterceptor().aroundObserver(invocation))
                    .isSameAs(result);
            verify(invocation).proceed();
            verifyNoMoreInteractions(invocation);
        }
    }
}
