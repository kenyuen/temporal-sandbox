package helloworldapp.durability;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionDescription;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.TemporalFailure;

import java.util.Optional;

/**
 * Reports how far a {@link DurableOrderWorkflow} execution has got, and how it ended once it has
 * closed.
 */
public class DurableOrderStatusChecker {

    private static final String STATUS_PREFIX = "WORKFLOW_EXECUTION_STATUS_";

    private final WorkflowClient client;

    /**
     * @param client the client used to describe, query and fetch results of order executions
     */
    public DurableOrderStatusChecker(WorkflowClient client) {
        this.client = client;
    }

    /**
     * What is known about an order execution.
     *
     * @param status the step a running order is on, or how a closed order ended
     * @param result the workflow's return value, present only once the order has completed
     */
    public record Report(String status, Optional<String> result) {}

    /**
     * Checks the latest run of the given order workflow.
     *
     * <p>A closed run is answered from the server's history alone, so it never needs a Worker.
     * Only a running order is queried for its current step, and that query waits until a Worker
     * is polling the task queue to answer it.
     *
     * @param workflowId the Workflow Id the order was started with
     * @return the order's current step, or its outcome once closed
     */
    public Report check(String workflowId) {
        WorkflowExecutionDescription description = client.newUntypedWorkflowStub(workflowId).describe();
        // Pinned to the described run, so a newer run reusing this Workflow Id can't answer in
        // its place.
        WorkflowExecution execution = description.getExecution();
        WorkflowStub run = client.newUntypedWorkflowStub(execution, Optional.empty());

        WorkflowExecutionStatus status = description.getStatus();
        return switch (status) {
            case WORKFLOW_EXECUTION_STATUS_RUNNING ->
                    new Report(run.query("getStatus", String.class), Optional.empty());
            case WORKFLOW_EXECUTION_STATUS_COMPLETED ->
                    new Report("Completed", Optional.of(run.getResult(String.class)));
            case WORKFLOW_EXECUTION_STATUS_FAILED,
                 WORKFLOW_EXECUTION_STATUS_TERMINATED,
                 WORKFLOW_EXECUTION_STATUS_TIMED_OUT,
                 WORKFLOW_EXECUTION_STATUS_CANCELED ->
                    new Report("Ended " + nameOf(status) + ": " + failureReason(run), Optional.empty());
            default -> new Report("Ended " + nameOf(status), Optional.empty());
        };
    }

    private static String nameOf(WorkflowExecutionStatus status) {
        return status.name().substring(STATUS_PREFIX.length());
    }

    // getResult rethrows a run's failure; its innermost Temporal failure carries the reason a
    // person would act on (the activity's error, the termination reason, ...).
    private static String failureReason(WorkflowStub run) {
        try {
            run.getResult(String.class);
            return "no failure recorded";
        } catch (WorkflowFailedException e) {
            String reason = e.getMessage();
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof TemporalFailure failure) {
                    reason = failure.getOriginalMessage();
                }
            }
            return reason;
        }
    }
}
