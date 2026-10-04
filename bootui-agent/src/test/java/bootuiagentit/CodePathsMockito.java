package bootuiagentit;

import bootuicodepathsapp.PriceClient;
import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.CodePaths;
import java.util.List;

/**
 * Mockito's inline mock maker beside the code-paths sensor, in both transformer orders: {@code bootui-first} claims
 * before the first mock, {@code mockito-first} creates a mock before the claim. Prints the fragments a stubbed call and
 * a spied real call leave, and whether the thread's depth is balanced after each.
 */
public final class CodePathsMockito {

    private CodePathsMockito() {}

    public static void main(String[] args) throws Exception {
        String order = args[0];
        PriceClient mock = null;
        if ("mockito-first".equals(order)) {
            mock = org.mockito.Mockito.mock(PriceClient.class);
        }
        CodePathsBehaviors.token =
                CodePathsBehaviors.claim(List.of("code-paths"), List.of(CodePathsBehaviors.APP + "PriceClient"));
        CodePathsBehaviors.awaitSelfTests(false);
        if (mock == null) {
            mock = org.mockito.Mockito.mock(PriceClient.class);
        }
        org.mockito.Mockito.when(mock.answer()).thenReturn(7);
        CodePathsBehaviors.drain();

        CodePathsBehaviors.CONTEXT.set(new String[] {CodePathsBehaviors.REQUEST, null});
        int stubbed = mock.answer();
        int stubbedDepth = CodePaths.depth();
        List<long[]> stubbedBlobs = CodePathsBehaviors.drain();
        PriceClient spy = org.mockito.Mockito.spy(new PriceClient());
        CodePathsBehaviors.drain();
        int real = spy.real();
        int spyDepth = CodePaths.depth();
        List<long[]> spyBlobs = CodePathsBehaviors.drain();
        CodePathsBehaviors.CONTEXT.remove();
        org.mockito.Mockito.verify(spy).real();

        System.out.println("MOCKITO=" + (stubbed == 7 && real == 1 ? "ok" : "unexpected " + stubbed + "/" + real));
        System.out.println("MOCK_CLASS=" + mock.getClass().getName());
        System.out.println("STUBBED=" + CodePathsBehaviors.describe(stubbedBlobs) + " depth " + stubbedDepth);
        System.out.println("SPY=" + CodePathsBehaviors.describe(spyBlobs) + " depth " + spyDepth);
        System.out.println("SENSOR=" + CodePathsBehaviors.sensor(CodePaths.SENSOR));
        System.out.println("STATUS=" + AgentBridge.status());
    }
}
