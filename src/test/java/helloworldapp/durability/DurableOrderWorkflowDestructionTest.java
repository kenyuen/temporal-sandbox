package helloworldapp.durability;

import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Demonstrates the mechanism behind Temporal's durability guarantee: a Workflow Execution's
 * progress lives entirely in the history the Temporal Server persists, not in any Worker's
 * memory. If the Worker process running it is destroyed, any other Worker can pick the same
 * Workflow Execution back up using nothing but that history.
 *
 * <p>The strongest way to prove that "nothing but history" claim is {@link
 * Worker#replayWorkflowExecution}: it takes a Workflow Execution's recorded history and replays
 * the Workflow's code against it in a brand new {@link Worker}, one that registers no Activities
 * and has never seen this order before. If the Workflow's code can deterministically reproduce
 * every decision the original run made -- with no in-memory hand-off between the two workers --
 * then a real Worker crash followed by a fresh Worker resuming the same Workflow Execution is
 * exactly this same mechanism, just live instead of replayed. See {@code
 * scripts/demonstrate-durability.sh} for that live version: it starts a real order, kills the
 * Worker process with {@code kill -9} while the order is mid-flight, and shows a freshly started
 * Worker process finish the order without redoing the steps already completed.
 */
class DurableOrderWorkflowDestructionTest {

    private static final String TASK_QUEUE = "DurableOrderDestructionTestQueue";

    private TestWorkflowEnvironment testEnv;

    @AfterEach
    void tearDown() {
        if (testEnv != null) {
            testEnv.close();
        }
    }

    @Test
    @Timeout(30)
    void freshWorkerReconstructsExecutionFromHistoryAlone() throws Exception {
        testEnv = TestWorkflowEnvironment.newInstance();

        AtomicInteger validateOrderCalls = new AtomicInteger();
        AtomicInteger reserveInventoryCalls = new AtomicInteger();
        AtomicInteger chargePaymentCalls = new AtomicInteger();
        AtomicInteger shipOrderCalls = new AtomicInteger();
        AtomicInteger sendConfirmationCalls = new AtomicInteger();

        OrderActivities activities =
                new OrderActivities() {
                    @Override
                    public String validateOrder(String orderId) {
                        validateOrderCalls.incrementAndGet();
                        return "validated";
                    }

                    @Override
                    public String reserveInventory(String orderId) {
                        reserveInventoryCalls.incrementAndGet();
                        return "reserved";
                    }

                    @Override
                    public String chargePayment(String orderId) {
                        chargePaymentCalls.incrementAndGet();
                        return "charged";
                    }

                    @Override
                    public String shipOrder(String orderId) {
                        shipOrderCalls.incrementAndGet();
                        return "shipped";
                    }

                    @Override
                    public String sendConfirmation(String orderId) {
                        sendConfirmationCalls.incrementAndGet();
                        return "confirmed";
                    }
                };

        // Worker "process" A runs the order to completion.
        Worker workerA = testEnv.newWorker(TASK_QUEUE);
        workerA.registerWorkflowImplementationTypes(DurableOrderWorkflowImpl.class);
        workerA.registerActivitiesImplementations(activities);
        testEnv.start();

        WorkflowOptions options =
                WorkflowOptions.newBuilder()
                        .setWorkflowId("destruction-test-" + System.nanoTime())
                        .setTaskQueue(TASK_QUEUE)
                        .build();
        DurableOrderWorkflow workflow =
                testEnv.getWorkflowClient().newWorkflowStub(DurableOrderWorkflow.class, options);
        String result = workflow.fulfillOrder("ORDER-1");

        assertEquals("Order ORDER-1 fulfilled successfully.", result);
        assertEquals(1, validateOrderCalls.get());
        assertEquals(1, reserveInventoryCalls.get());
        assertEquals(1, chargePaymentCalls.get());
        assertEquals(1, shipOrderCalls.get());
        assertEquals(1, sendConfirmationCalls.get());

        // Everything worker A knew is now gone; all that's left is what the server persisted.
        WorkflowExecutionHistory history =
                testEnv.getWorkflowExecutionHistory(WorkflowStub.fromTyped(workflow).getExecution());

        // Worker "process" B: a brand new WorkerFactory/Worker with no Activities registered and
        // no memory of order ORDER-1. It reconstructs the entire execution -- which steps ran,
        // in which order, with which results -- purely by replaying the persisted history through
        // the same Workflow code.
        WorkerFactory freshFactory = WorkerFactory.newInstance(testEnv.getWorkflowClient());
        Worker freshWorker = freshFactory.newWorker(TASK_QUEUE + "-worker-b");
        freshWorker.registerWorkflowImplementationTypes(DurableOrderWorkflowImpl.class);

        assertDoesNotThrow(
                () -> freshWorker.replayWorkflowExecution(history),
                "a worker with no knowledge of this order failed to reconstruct it from history"
                        + " alone -- the workflow's progress must not depend on worker memory");
    }
}
