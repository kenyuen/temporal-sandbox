package helloworldapp.durability;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;

/**
 * Reports the current step of a running {@link DurableOrderWorkflow}, or its outcome once it has
 * closed. A running order's step is answered by a Worker; if none is polling the task queue, this
 * waits until one is (proving that the workflow's state lives on the server, not in worker
 * memory). A closed order's outcome comes from the server alone.
 */
public class CheckDurableOrderStatus {

    /**
     * Prints {@code STATUS: <step or outcome>}, followed by {@code RESULT: <result>} once the
     * order has completed.
     *
     * @param args the Workflow Id of the order to check
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: CheckDurableOrderStatus <workflowId>");
            System.exit(1);
        }
        String workflowId = args[0];

        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        try {
            DurableOrderStatusChecker.Report report =
                    new DurableOrderStatusChecker(WorkflowClient.newInstance(service)).check(workflowId);
            System.out.println("STATUS: " + report.status());
            report.result().ifPresent(result -> System.out.println("RESULT: " + result));
        } finally {
            service.shutdown();
        }
    }
}
