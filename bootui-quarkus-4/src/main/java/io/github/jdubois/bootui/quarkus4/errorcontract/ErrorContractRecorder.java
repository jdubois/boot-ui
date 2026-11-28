package io.github.jdubois.bootui.quarkus4.errorcontract;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import java.util.List;

/**
 * Quarkus recorder that replays the build-time-captured error-contract declarations into a runtime
 * {@link QuarkusErrorContract} holder.
 *
 * <p>The deployment processor's {@code registerErrorContract} build step scans the build-time Jandex index
 * for Jakarta REST {@code @Provider} {@code ExceptionMapper} implementations and Quarkus REST
 * {@code @ServerExceptionMapper} methods, builds the {@link RawErrorHandler} list, and calls
 * {@link #create(List)} from a {@code @Record(STATIC_INIT)} step; the returned {@link RuntimeValue} backs a
 * synthetic {@link QuarkusErrorContract} bean. Nothing here instantiates or invokes a mapper.</p>
 */
@Recorder
public class ErrorContractRecorder {

    /** Wraps the captured rows in a runtime holder backing the synthetic {@link QuarkusErrorContract} bean. */
    public RuntimeValue<QuarkusErrorContract> create(List<RawErrorHandler> handlers) {
        return new RuntimeValue<>(new QuarkusErrorContract(handlers));
    }
}
