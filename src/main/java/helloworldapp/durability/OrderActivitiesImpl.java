package helloworldapp.durability;

public class OrderActivitiesImpl implements OrderActivities {

    @Override
    public String validateOrder(String orderId) {
        return logAndComplete(orderId, "Validated order");
    }

    @Override
    public String reserveInventory(String orderId) {
        return logAndComplete(orderId, "Reserved inventory");
    }

    @Override
    public String chargePayment(String orderId) {
        return logAndComplete(orderId, "Charged payment");
    }

    @Override
    public String shipOrder(String orderId) {
        return logAndComplete(orderId, "Shipped order");
    }

    @Override
    public String sendConfirmation(String orderId) {
        return logAndComplete(orderId, "Sent confirmation");
    }

    // Includes the OS process id so the durability demo can show that a step completed after a
    // worker restart was executed by a different worker process than the steps before it.
    private String logAndComplete(String orderId, String step) {
        System.out.printf("[worker pid %d] %s for %s%n", ProcessHandle.current().pid(), step, orderId);
        return step;
    }
}
