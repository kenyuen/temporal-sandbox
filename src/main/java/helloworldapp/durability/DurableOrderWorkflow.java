package helloworldapp.durability;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * A multi-step order fulfillment saga used to demonstrate Temporal's durability guarantee: the
 * Workflow Execution survives the Worker process that runs it being destroyed mid-execution. See
 * {@code scripts/demonstrate-durability.sh} for a live "kill -9 the worker" walkthrough, and
 * {@code DurableOrderWorkflowDestructionTest} for an automated version of the same scenario.
 */
@WorkflowInterface
public interface DurableOrderWorkflow {

    @WorkflowMethod
    String fulfillOrder(String orderId);

    /** Reports which step the order is currently on, so progress can be observed from outside. */
    @QueryMethod
    String getStatus();
}
