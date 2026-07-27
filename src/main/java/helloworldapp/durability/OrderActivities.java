package helloworldapp.durability;

import io.temporal.activity.ActivityInterface;

/**
 * The steps involved in fulfilling an order. Each method is executed outside the Workflow
 * thread, on an Activity Worker, and may have side effects.
 */
@ActivityInterface
public interface OrderActivities {

    String validateOrder(String orderId);

    String reserveInventory(String orderId);

    String chargePayment(String orderId);

    String shipOrder(String orderId);

    String sendConfirmation(String orderId);
}
