package helloworldapp.durability;

import helloworldapp.Shared;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

/**
 * Runs a Worker for the durability demo. Intended to be killed (e.g. {@code kill -9}) and
 * restarted mid-workflow to show that the running Workflow Execution is unaffected -- see
 * {@code scripts/demonstrate-durability.sh}.
 */
public class DurableOrderWorker {

    public static void main(String[] args) {
        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);

        Worker worker = factory.newWorker(Shared.DURABLE_ORDER_TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(DurableOrderWorkflowImpl.class);
        worker.registerActivitiesImplementations(new OrderActivitiesImpl());

        System.out.println("Durable order worker starting (pid " + ProcessHandle.current().pid() + ")");
        factory.start();
    }
}
