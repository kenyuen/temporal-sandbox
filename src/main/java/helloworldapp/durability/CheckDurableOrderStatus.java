package helloworldapp.durability;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;

/**
 * Queries the current step of a running {@link DurableOrderWorkflow}, and reports its result if
 * it has finished. Answering the query requires a Worker to be polling the task queue; if none
 * is running, this call blocks until one is (proving that the workflow's state lives on the
 * server, not in worker memory).
 */
public class CheckDurableOrderStatus {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: CheckDurableOrderStatus <workflowId>");
            System.exit(1);
        }
        String workflowId = args[0];

        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);

        String status = stub.query("getStatus", String.class);
        System.out.println("STATUS: " + status);
    }
}
