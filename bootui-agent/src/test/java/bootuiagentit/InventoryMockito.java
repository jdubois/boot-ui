package bootuiagentit;

import bootuiinventoryapp.Stubbed;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Mockito's inline mock maker beside the inventory sensor, in both transformer orders: {@code bootui-first} claims (so
 * the sensor's transformer is registered) before the first mock, {@code mockito-first} creates a mock (so Mockito's
 * transformer is registered and the class retransformed) before the claim. Prints whether a stubbed call and a spied
 * real call count as executed.
 */
public final class InventoryMockito {

    private InventoryMockito() {}

    public static void main(String[] args) throws Exception {
        String order = args[0];
        Stubbed mock = null;
        if ("mockito-first".equals(order)) {
            mock = org.mockito.Mockito.mock(Stubbed.class);
        }
        InventoryBehaviors.bridge = Class.forName(InventoryBehaviors.BRIDGE + "AgentBridge", true, null);
        InventoryBehaviors.inventory = Class.forName(InventoryBehaviors.BRIDGE + "CodeInventory", true, null);
        InventoryBehaviors.ring = Class.forName(InventoryBehaviors.BRIDGE + "AgentRing", true, null);
        Object marker = new Object();
        Supplier<Object> capture = () -> marker == null ? null : null;
        Function<Object, AutoCloseable> reopen = snapshot -> null;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "inventory-mockito");
        request.put("mode", "dev");
        request.put("packages", List.of("bootuiinventoryapp"));
        request.put("sensors", List.of("inventory"));
        Object result = InventoryBehaviors.bridge
                .getMethod("claim", Map.class, Supplier.class, Function.class)
                .invoke(null, request, capture, reopen);
        System.out.println("CLAIM=" + ((Map<?, ?>) result).get("status"));
        InventoryBehaviors.awaitSelfTest();
        if (mock == null) {
            mock = org.mockito.Mockito.mock(Stubbed.class);
        }
        org.mockito.Mockito.when(mock.answer()).thenReturn(7);
        int stubbed = mock.answer();
        Stubbed spy = org.mockito.Mockito.spy(new Stubbed());
        int real = spy.real();
        org.mockito.Mockito.verify(spy).real();
        System.out.println("MOCKITO=" + (stubbed == 7 && real == 1 ? "ok" : "unexpected " + stubbed + "/" + real));
        System.out.println("MOCK_CLASS=" + mock.getClass().getName());
        System.out.println("STUBBED_EXECUTED=" + InventoryBehaviors.executed("bootuiinventoryapp.Stubbed#answer()I"));
        System.out.println("SPY_EXECUTED=" + InventoryBehaviors.executed("bootuiinventoryapp.Stubbed#real()I"));
        System.out.println("SENSOR=" + InventoryBehaviors.sensor());
        System.out.println("STATUS=" + InventoryBehaviors.status());
        System.out.println("CAPTURE_ALIVE=" + (capture != null && reopen != null));
    }
}
