package bootuicodepathsapp;

import java.util.concurrent.ExecutorService;

/** A bean the code-paths sensor instruments, calling another bean. */
public class OrderController {

    private final OrderService service = new OrderService();

    public int place(int quantity) {
        int price = service.price(quantity);
        return price + service.tax(price);
    }

    public int failing() {
        try {
            service.fail();
            return 0;
        } catch (IllegalStateException expected) {
            return -1;
        }
    }

    /** Hands a bean call to an executor: its fragment runs on the worker, under the request's child execution. */
    public int handoff(ExecutorService pool) throws Exception {
        return pool.submit(() -> service.price(2)).get();
    }

    public int excluded() {
        return service.callExcluded();
    }
}
