package helloworldapp.durability;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurableOrderWorkflowTest {

    @RegisterExtension
    static final TestWorkflowExtension testWorkflowExtension =
            TestWorkflowExtension.newBuilder()
                    .setWorkflowTypes(DurableOrderWorkflowImpl.class)
                    .setDoNotStart(true)
                    .build();

    @Test
    void fulfillsOrderThroughAllSteps(
            TestWorkflowEnvironment testEnv, Worker worker, WorkflowClient client, WorkflowOptions options) {
        worker.registerActivitiesImplementations(new OrderActivitiesImpl());
        testEnv.start();

        DurableOrderWorkflow workflow = client.newWorkflowStub(DurableOrderWorkflow.class, options);
        String result = workflow.fulfillOrder("ORDER-42");

        assertEquals("Order ORDER-42 fulfilled successfully.", result);
        testEnv.shutdown();
    }
}
