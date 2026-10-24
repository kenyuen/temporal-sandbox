package helloworldapp.durability;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class DurableOrderStatusCheckerTest {

    private static final String TASK_QUEUE = "DurableOrderStatusCheckerTestQueue";

    private TestWorkflowEnvironment testEnv;
    private WorkflowClient client;
    private ControllableActivities activities;
    private DurableOrderStatusChecker checker;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        activities = new ControllableActivities();
        Worker worker = testEnv.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(DurableOrderWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        testEnv.start();
        client = testEnv.getWorkflowClient();
        checker = new DurableOrderStatusChecker(client);
    }

    @AfterEach
    void tearDown() {
        activities.releaseValidation();
        testEnv.close();
    }

    @Test
    void reportsResultOfCompletedOrder() {
        String workflowId = "completed-" + System.nanoTime();
        newOrder(workflowId).fulfillOrder("ORDER-1");

        assertEquals(
                new DurableOrderStatusChecker.Report("Completed", Optional.of("Order ORDER-1 fulfilled successfully.")),
                checker.check(workflowId));
    }

    @Test
    void reportsCompletedOrderWithoutAnyWorkerRunning() throws InterruptedException {
        String workflowId = "no-worker-" + System.nanoTime();
        newOrder(workflowId).fulfillOrder("ORDER-2");
        testEnv.getWorkerFactory().shutdown();
        testEnv.getWorkerFactory().awaitTermination(10, TimeUnit.SECONDS);

        // Would block forever if a closed order were still answered by querying a Worker.
        assertEquals(
                new DurableOrderStatusChecker.Report("Completed", Optional.of("Order ORDER-2 fulfilled successfully.")),
                checker.check(workflowId));
    }

    @Test
    void reportsCurrentStepOfRunningOrder() throws InterruptedException {
        String workflowId = "running-" + System.nanoTime();
        activities.blockValidation();
        WorkflowClient.start(newOrder(workflowId)::fulfillOrder, "ORDER-3");
        activities.awaitValidationStarted();

        assertEquals(
                new DurableOrderStatusChecker.Report("Step 1/5: Validating order", Optional.empty()),
                checker.check(workflowId));
    }

    @Test
    void reportsWhyFailedOrderFailed() {
        String workflowId = "failed-" + System.nanoTime();
        activities.declinePayment();
        assertThrows(WorkflowFailedException.class, () -> newOrder(workflowId).fulfillOrder("ORDER-4"));

        assertEquals(
                new DurableOrderStatusChecker.Report("Ended FAILED: card declined", Optional.empty()),
                checker.check(workflowId));
    }

    @Test
    void reportsWhyTerminatedOrderWasTerminated() throws InterruptedException {
        String workflowId = "terminated-" + System.nanoTime();
        activities.blockValidation();
        WorkflowClient.start(newOrder(workflowId)::fulfillOrder, "ORDER-5");
        activities.awaitValidationStarted();
        client.newUntypedWorkflowStub(workflowId).terminate("operator stopped it");

        assertEquals(
                new DurableOrderStatusChecker.Report("Ended TERMINATED: operator stopped it", Optional.empty()),
                checker.check(workflowId));
    }

    @Test
    void reportsLatestRunWhenWorkflowIdIsReused() throws InterruptedException {
        String workflowId = "reused-" + System.nanoTime();
        newOrder(workflowId).fulfillOrder("ORDER-6");
        activities.blockValidation();
        WorkflowClient.start(newOrder(workflowId)::fulfillOrder, "ORDER-6");
        activities.awaitValidationStarted();

        DurableOrderStatusChecker.Report report = checker.check(workflowId);

        assertEquals("Step 1/5: Validating order", report.status());
        assertTrue(report.result().isEmpty(), "the earlier, completed run's result leaked into the report");
    }

    private DurableOrderWorkflow newOrder(String workflowId) {
        return client.newWorkflowStub(
                DurableOrderWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue(TASK_QUEUE).build());
    }

    /** Lets a test hold an order in step 1, or fail it at the payment step. */
    private static class ControllableActivities implements OrderActivities {

        private final CountDownLatch validationStarted = new CountDownLatch(1);
        private final CountDownLatch validationReleased = new CountDownLatch(1);
        private volatile boolean blockValidation;
        private volatile boolean declinePayment;

        void blockValidation() {
            blockValidation = true;
        }

        void awaitValidationStarted() throws InterruptedException {
            assertTrue(validationStarted.await(10, TimeUnit.SECONDS), "validateOrder never started");
        }

        void releaseValidation() {
            validationReleased.countDown();
        }

        void declinePayment() {
            declinePayment = true;
        }

        @Override
        public String validateOrder(String orderId) {
            if (blockValidation) {
                validationStarted.countDown();
                try {
                    validationReleased.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return "validated";
        }

        @Override
        public String reserveInventory(String orderId) {
            return "reserved";
        }

        @Override
        public String chargePayment(String orderId) {
            if (declinePayment) {
                throw ApplicationFailure.newNonRetryableFailure("card declined", "PaymentDeclined");
            }
            return "charged";
        }

        @Override
        public String shipOrder(String orderId) {
            return "shipped";
        }

        @Override
        public String sendConfirmation(String orderId) {
            return "confirmed";
        }
    }
}
