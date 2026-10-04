package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.CodePaths;
import net.bytebuddy.asm.Advice;

/**
 * The code-paths sensor's advice (PLAN-v2 §5.14, M5-4a), inlined around every instrumented bean method: one static call
 * at entry, returning the call's token, and one at every exit, normal or by a throwable, with that token. Both suppress
 * their own exceptions, and a suppressed entry leaves the token 0, the no-op sentinel, so entry and exit always balance.
 * The method's id is bound as a constant ({@link InventoryAdvice.MethodId}), shared with the inventory sensor's ids.
 */
final class CodePathsAdvice {

    private CodePathsAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static int enter(@InventoryAdvice.MethodId int id) {
        return CodePaths.enter(id);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(@Advice.Enter int token) {
        CodePaths.exit(token);
    }
}
