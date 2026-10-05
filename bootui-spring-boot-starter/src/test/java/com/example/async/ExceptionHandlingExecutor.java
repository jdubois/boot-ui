package com.example.async;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The shape of JHipster's {@code ExceptionHandlingAsyncTaskExecutor}, outside BootUI's packages as an application's
 * would be: an executor bean wrapping a pool that is not a bean, initialized and destroyed through the wrapper.
 */
public final class ExceptionHandlingExecutor implements Executor, InitializingBean, DisposableBean {

    private final ThreadPoolTaskExecutor executor;

    public ExceptionHandlingExecutor(ThreadPoolTaskExecutor executor) {
        this.executor = executor;
    }

    @Override
    public void execute(Runnable task) {
        executor.execute(task);
    }

    @Override
    public void afterPropertiesSet() {
        executor.afterPropertiesSet();
    }

    @Override
    public void destroy() {
        executor.destroy();
    }
}
