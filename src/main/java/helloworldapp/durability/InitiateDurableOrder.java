package helloworldapp.durability;

import helloworldapp.Shared;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;

/**
 * Starts a {@link DurableOrderWorkflow} execution and returns immediately (does not wait for
 * completion), so its Worker can be killed mid-flight for the durability demo. Prints the
 * Workflow Id so {@link CheckDurableOrderStatus} can query it.
 */
public class InitiateDurableOrder {

    public static void main(String[] args) {
        String orderId = args.length > 0 ? args[0] : "ORDER-" + System.currentTimeMillis();
        String workflowId = "DurableOrder-" + orderId;

        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);

        WorkflowOptions options =
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(Shared.DURABLE_ORDER_TASK_QUEUE)
                        .build();

        DurableOrderWorkflow workflow = client.newWorkflowStub(DurableOrderWorkflow.class, options);

        // Start asynchronously: don't block waiting for the result, so this process can exit
        // while the workflow keeps running on the server, independent of any worker.
        WorkflowClient.start(workflow::fulfillOrder, orderId);

        System.out.println("Started workflow " + workflowId);
        System.out.println("Check its progress with:");
        System.out.println(
                "  mvn exec:java -Dexec.mainClass=\"helloworldapp.durability.CheckDurableOrderStatus\" -Dexec.args=\""
                        + workflowId
                        + "\"");
    }
}
