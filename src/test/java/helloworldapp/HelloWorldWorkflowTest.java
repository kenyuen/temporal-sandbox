package helloworldapp;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class HelloWorldWorkflowTest {

    @RegisterExtension
    public static final TestWorkflowExtension testWorkflowExtension =
            TestWorkflowExtension.newBuilder()
                    .setWorkflowTypes(HelloWorldWorkflowImpl.class)
                    .setDoNotStart(true)
                    .build();

    @Test
    public void testIntegrationGetGreeting(
            TestWorkflowEnvironment testEnv, Worker worker, WorkflowClient client, WorkflowOptions options) {
        worker.registerActivitiesImplementations(new HelloWorldActivitiesImpl());
        testEnv.start();

        HelloWorldWorkflow workflow = client.newWorkflowStub(HelloWorldWorkflow.class, options);
        String greeting = workflow.getGreeting("John");
        assertEquals("Hello John!", greeting);
        testEnv.shutdown();
    }

    @Test
    public void testMockedGetGreeting(
            TestWorkflowEnvironment testEnv, Worker worker, WorkflowClient client, WorkflowOptions options) {
        HelloWorldActivities formatActivities = mock(HelloWorldActivities.class, withSettings().withoutAnnotations());
        when(formatActivities.composeGreeting(anyString())).thenReturn("Hello World!");
        worker.registerActivitiesImplementations(formatActivities);
        testEnv.start();

        HelloWorldWorkflow workflow = client.newWorkflowStub(HelloWorldWorkflow.class, options);
        String greeting = workflow.getGreeting("World");
        assertEquals("Hello World!", greeting);
        testEnv.shutdown();
    }
}
