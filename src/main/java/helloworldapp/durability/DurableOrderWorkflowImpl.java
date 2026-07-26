package helloworldapp.durability;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.Map;

public class DurableOrderWorkflowImpl implements DurableOrderWorkflow {

    // Gap between steps. Long enough in the live demo to give "kill -9 the worker" a window to
    // land mid-execution; skipped instantly by the time-skipping test environment in unit tests.
    private static final Duration STEP_PAUSE = Duration.ofSeconds(8);

    private final ActivityOptions defaultOptions =
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(60)).build();

    // The inventory system is expected to respond within a few seconds; a short timeout here lets
    // Temporal notice a stuck attempt (e.g. its worker died) and retry it on another worker
    // instead of waiting out the default 60s.
    private final ActivityOptions reserveInventoryOptions =
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build();

    private final OrderActivities activity =
            Workflow.newActivityStub(
                    OrderActivities.class,
                    defaultOptions,
                    Map.of("ReserveInventory", reserveInventoryOptions));

    private String status = "Not started";

    @Override
    public String fulfillOrder(String orderId) {
        status = "Step 1/5: Validating order";
        activity.validateOrder(orderId);
        Workflow.sleep(STEP_PAUSE);

        status = "Step 2/5: Reserving inventory";
        activity.reserveInventory(orderId);
        Workflow.sleep(STEP_PAUSE);

        status = "Step 3/5: Charging payment";
        activity.chargePayment(orderId);
        Workflow.sleep(STEP_PAUSE);

        status = "Step 4/5: Shipping order";
        activity.shipOrder(orderId);
        Workflow.sleep(STEP_PAUSE);

        status = "Step 5/5: Sending confirmation";
        activity.sendConfirmation(orderId);

        status = "Completed";
        return "Order " + orderId + " fulfilled successfully.";
    }

    @Override
    public String getStatus() {
        return status;
    }
}
